package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import ai.yaay.documents.types.builtin.BuiltInTypes
import kotlin.test.*

class WorkspaceTreeTest {
    private class Peers {
        val keys = List(3) { JvmBatchCrypto.generate() }
        val replicas = keys.map { ReplicaEngine("tree", it.author, keys[0].author, it, MemoryJournal(), TypedMutationValidator()) }
        val a = replicas[0]
        init { a.commit(keys.drop(1).map { Operation.Membership(it.author, true) }); sync() }
        fun sync() { val all = replicas.flatMap { it.history }.distinct(); replicas.forEach { r -> all.forEach { r.ingest(it, keys[0].author) } } }
        fun edit(replica: ReplicaEngine = a, block: TypedEdit.() -> Unit): SignedBatch = replica.commit(TypedEdit(replica::nextId, replica.snapshot).apply(block).operations)
        fun define(text: String): Map<String, String> {
            val declarations = TypeSyntax.parseDeclarations(text, TypeCatalog(a.snapshot), a::nextId)
            val ids = declarations.indices.map { a.nextId(it) }
            a.commit(declarations.mapIndexed { i, d -> TypeEncoding.publish(ids[i], d.definition, d.name) })
            sync()
            return declarations.map { it.name }.zip(ids).toMap()
        }
        fun tree(replica: ReplicaEngine = a) = WorkspaceTree(replica.snapshot)
        fun assertConverged() { replicas.forEach { assertEquals(tree(a).topLevel(), tree(it).topLevel()); assertEquals(a.snapshot, it.snapshot) } }
    }
    private val ada = Value.Record(mapOf("name" to Value.Atomic(Atom.Str("Ada"))))
    private fun person(peers: Peers) = Type.Named(peers.define("type Person = { name: String }").getValue("Person"))
    private fun childrenOf(peers: Peers, folder: String, replica: ReplicaEngine = peers.a) = peers.tree(replica).childrenList(folder)

    @Test fun anyHoldsAnyConcreteTypeButNeverTreeTypesKeysOrItself() {
        val peers = Peers()
        val person = person(peers)
        var doc = ""; var notes = ""; var list = ""
        peers.edit {
            doc = newDocument("Ada", Value.Typed(person, ada))
            notes = newDocument("Notes", Value.Typed(Type.Scalar.TEXT, Value.Text("hello")))
            list = newDocument("Tags", Value.Typed(Type.Sequence(Type.Scalar.STRING), Value.Sequence(listOf(Value.Atomic(Atom.Str("x"))))))
        }
        assertEquals(listOf("Ada", "Notes", "Tags"), peers.tree().topLevel().map { it.title })
        val content = peers.tree().entry(doc)!!.inner!!
        assertEquals(person, TypeEncoding.decode(peers.a.snapshot[content].type))
        // A tree node cannot hide inside an Any slot.
        val folder = Value.Variant("Folder", Value.Record(mapOf("title" to Value.Atomic(Atom.Str("f")), "children" to Value.Sequence(emptyList()))))
        val sneaky = assertFailsWith<IllegalArgumentException> { peers.edit { newDocument("Sneaky", Value.Typed(BuiltInTypes.node, folder)) } }
        assertEquals("A node belongs at the top level or in a folder", sneaky.message)
        val any = assertFailsWith<IllegalArgumentException> { peers.edit { create(Type.Any, ada) } }
        assertEquals("Any is a slot type; create a value of a concrete type", any.message)
        val key = assertFailsWith<IllegalArgumentException> { peers.define("type Bad = Map<Any, String>") }
        assertEquals("Any cannot be a map key type", key.message)
        val shape = Type.Record(mapOf("content" to Type.Any, "items" to Type.Sequence(Type.Any), "link" to Type.Ref(Type.Any)))
        assertEquals(shape, TypeEncoding.decode(TypeEncoding.encode(shape)))
        assertEquals(listOf(doc, notes, list).sorted(), peers.tree().topLevel().map { it.node }.sorted())
    }

    @Test fun userTypesMayReferenceTreeTypesButNeverEmbedThem() {
        val peers = Peers()
        peers.define("type Link = { target: Ref<Document>, anyNode: Ref<Node> }")
        for (embedding in listOf("type Holder = { doc: Document }", "type Many = List<Node>", "type Box<T> = { v: T }\ntype Boxed = Box<Folder>", "type Box<T> = { v: T }\ntype Sneaky = Ref<Box<Document>>")) {
            val failure = assertFailsWith<IllegalArgumentException>(embedding) { peers.define(embedding) }
            assertEquals("Node, Folder and Document can only be referenced (Ref<...>), not embedded", failure.message, embedding)
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { peers.define("type Document = String") }.message.orEmpty().contains("reserved"))
        // Inline types get the same protection from placement rules, locally and from a peer.
        val looseList = assertFailsWith<IllegalArgumentException> { peers.edit { create(Type.Sequence(BuiltInTypes.node), Value.Sequence(emptyList())) } }
        assertEquals("A list of nodes exists only as a folder's children", looseList.message)
        val bareDocument = Value.Record(mapOf("title" to Value.Atomic(Atom.Str("x")), "content" to Value.Typed(Type.Scalar.STRING, Value.Atomic(Atom.Str("y")))))
        val loose = assertFailsWith<IllegalArgumentException> { peers.edit { create(BuiltInTypes.document, bareDocument) } }
        assertEquals("Folders and documents exist only inside a node", loose.message)
        val remote = peers.replicas[1]
        val id = BatchId(remote.author, remote.snapshot.frontier[remote.author] + 1)
        val ops = TypedEdit({ OpId(id, it).stableId() }, remote.snapshot).apply { create(Type.Scalar.STRING, Value.Atomic(Atom.Str("y"))) }.operations.let { content ->
            content + Operation.Create(OpId(id, 1).stableId(), TypeEncoding.encode(BuiltInTypes.document), Shape.RECORD, mapOf("title" to Atom.Str("x"), "content" to Atom.Instance(OpId(id, 0).stableId())))
        }
        val forged = peers.keys[1].sign(Batch("tree", 1, id, Frontier(remote.snapshot.frontier.counters + (remote.author to id.counter)), ops))
        val before = peers.a.snapshot
        assertFailsWith<IllegalArgumentException> { peers.a.ingest(forged, remote.author) }
        assertEquals(before, peers.a.snapshot)
    }

    @Test fun movingADocumentBetweenFoldersKeepsItsIdentityAndReferences() {
        val peers = Peers()
        val person = person(peers)
        val link = Type.Named(peers.define("type Link = { target: Ref<Document> }").getValue("Link"))
        var x = ""; var y = ""
        peers.edit { x = newFolder("X"); y = newFolder("Y") }
        var doc = ""
        peers.edit { doc = newDocument("Ada", Value.Typed(person, ada), childrenOf(peers, x)) }
        var pointer = ""
        peers.edit { pointer = create(link, Value.Record(mapOf("target" to Value.Atomic(Atom.Ref(peers.tree().payload(doc)))))) }
        peers.edit(peers.replicas[1].also { peers.sync() }) { move(doc, childrenOf(peers, y, peers.replicas[1])) }
        peers.sync()
        val tree = peers.tree().topLevel().associateBy { it.title }
        assertEquals(emptyList(), tree.getValue("X").children)
        assertEquals(listOf(doc), tree.getValue("Y").children.map { it.node })
        val target = (peers.a.snapshot[pointer].fields["target"] as Atom.Ref).id
        assertEquals(peers.tree().payload(doc), target)
        assertFalse(peers.a.snapshot[target].deleted)
        peers.assertConverged()
    }

    @Test fun concurrentMovesConvergeOnTheSmallestDestination() {
        val peers = Peers()
        var x = ""; var y = ""; var doc = ""
        peers.edit { x = newFolder("X"); y = newFolder("Y") }
        peers.edit { doc = newDocument("D", Value.Typed(Type.Scalar.STRING, Value.Atomic(Atom.Str("d")))) }
        peers.sync()
        val (b, c) = peers.replicas[1] to peers.replicas[2]
        peers.edit(b) { move(doc, childrenOf(peers, x, b)) }
        peers.edit(c) { move(doc, childrenOf(peers, y, c)) }
        peers.sync()
        val winner = minOf(childrenOf(peers, x), childrenOf(peers, y))
        assertEquals(winner, peers.a.snapshot[doc].parent)
        peers.assertConverged()
    }

    @Test fun cyclesAreRejectedLocallyAndResolvedIntoAValidTreeWhenConcurrent() {
        val peers = Peers()
        var x = ""; var inner = ""; var y = ""
        peers.edit { x = newFolder("X"); y = newFolder("Y") }
        peers.edit { inner = newFolder("Inner", childrenOf(peers, x)) }
        assertFailsWith<IllegalArgumentException> { peers.edit { move(x, childrenOf(peers, inner)) } }
        peers.sync()
        val (b, c) = peers.replicas[1] to peers.replicas[2]
        peers.edit(b) { move(x, childrenOf(peers, y, b)) }
        peers.edit(c) { move(y, childrenOf(peers, x, c)) }
        peers.sync()
        // Exactly one move survives; both folders stay visible and neither contains the other twice.
        fun all(entries: List<WorkspaceTree.Entry>): List<String> = entries.flatMap { listOf(it.node) + all(it.children) }
        assertEquals(setOf(x, y, inner), all(peers.tree().topLevel()).toSet())
        assertEquals(3, all(peers.tree().topLevel()).size)
        assertEquals(1, peers.tree().topLevel().size)
        peers.assertConverged()
    }

    @Test fun deletingAFolderHidesItsSubtreeButAConcurrentMoveOutSurvives() {
        val peers = Peers()
        var folder = ""; var kept = ""; var rescued = ""
        peers.edit { folder = newFolder("Old") }
        peers.edit {
            kept = newDocument("Inside", Value.Typed(Type.Scalar.STRING, Value.Atomic(Atom.Str("a"))), childrenOf(peers, folder))
            rescued = newDocument("Rescued", Value.Typed(Type.Scalar.STRING, Value.Atomic(Atom.Str("b"))), childrenOf(peers, folder), kept)
        }
        peers.sync()
        peers.edit(peers.replicas[1]) { delete(folder) }
        peers.edit(peers.replicas[2]) { move(rescued, null) }
        peers.sync()
        assertEquals(listOf(rescued), peers.tree().topLevel().map { it.node })
        assertNull(peers.tree().entry(kept))
        assertNull(peers.tree().entry(folder))
        peers.assertConverged()
    }

    @Test fun concurrentRenamesConvergeAndAnyValuesRoundTripThroughTheSyntax() {
        val peers = Peers()
        val person = person(peers)
        var doc = ""
        peers.edit { doc = newDocument("Draft", Value.Typed(person, ada)) }
        peers.sync()
        val payload = peers.tree().payload(doc)
        peers.edit(peers.replicas[1]) { rename(payload, "Left") }
        peers.edit(peers.replicas[2]) { rename(payload, "Right") }
        peers.sync()
        assertTrue(peers.tree().entry(doc)!!.title in setOf("Left", "Right"))
        peers.assertConverged()
        val syntax = ValueSyntax(peers.a.snapshot)
        val rendered = syntax.render(payload)
        assertTrue(rendered.startsWith("{ content: Person { name: \"Ada\" }, title: "), rendered)
        assertEquals(Value.Typed(person, ada), (syntax.parse(rendered, BuiltInTypes.document) as Value.Record).fields["content"])
    }
}
