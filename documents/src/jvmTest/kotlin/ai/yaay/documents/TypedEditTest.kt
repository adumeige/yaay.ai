package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import kotlin.test.*
import kotlin.random.Random
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec

class TypedEditTest {
    private class Peers(seed: Int = 0) {
        val keys = List(3) { index ->
            val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed("test-only:$seed:$index".toByteArray()) }
            JvmBatchCrypto(KeyPairGenerator.getInstance("Ed25519").apply { initialize(NamedParameterSpec("Ed25519"), random) }.generateKeyPair())
        }
        val replicas = keys.map { ReplicaEngine("edits", it.author, keys[0].author, it, MemoryJournal(), TypedMutationValidator()) }
        init { replicas[0].commit(keys.drop(1).map { Operation.Membership(it.author, true) }); sync() }
        fun sync() { val all = replicas.flatMap { it.history }.distinct(); replicas.forEach { r -> all.reversed().forEach { r.ingest(it, keys[0].author) } } }
    }
    private fun edit(replica: ReplicaEngine, block: TypedEdit.() -> Unit): SignedBatch {
        val edit = TypedEdit(replica::nextId, replica.snapshot)
        edit.block()
        return replica.commit(edit.operations)
    }
    private fun string(value: String): Value = Value.Atomic(Atom.Str(value))

    @Test fun nestedReplacementKeepsConcurrentOldPayloadEditsOutOfSelectedValue() {
        val peers = Peers()
        val a = peers.replicas[0]
        val childType = Type.Record(mapOf("label" to Type.Scalar.STRING))
        val parentType = Type.Record(mapOf("child" to childType, "items" to Type.Sequence(Type.Scalar.STRING)))
        var parent = ""
        edit(a) { parent = create(parentType, Value.Record(mapOf("child" to Value.Record(mapOf("label" to string("old"))), "items" to Value.Sequence(listOf(string("first")))))) }
        peers.sync()
        val old = (a.snapshot[parent].fields["child"] as Atom.Instance).id
        edit(a) { assign(parent, "child", Value.Record(mapOf("label" to string("replacement")))) }
        edit(peers.replicas[1]) { assign(old, "label", string("concurrent")) }
        peers.sync()
        val current = (a.snapshot[parent].fields["child"] as Atom.Instance).id
        assertNotEquals(old, current)
        peers.replicas.forEach {
            assertEquals(Atom.Str("replacement"), it.snapshot[current].fields["label"])
            assertEquals(a.snapshot.genericValue(parent), it.snapshot.genericValue(parent))
        }
    }
    @Test fun identifiedMapEntriesMergeFieldsDeleteAndRestoreWithNewIdentity() {
        val peers = Peers()
        val a = peers.replicas[0]
        val entry = Type.Record(mapOf("x" to Type.Scalar.STRING, "y" to Type.Scalar.STRING))
        var map = ""
        edit(a) { map = create(Type.MapOf(entry), Value.MapEntries(mapOf("key" to Value.Record(mapOf("x" to string("0"), "y" to string("0")))))) }
        peers.sync()
        val value = (a.snapshot[map].fields["key"] as Atom.Instance).id
        edit(a) { assign(value, "x", string("a")) }
        edit(peers.replicas[1]) { assign(value, "y", string("b")); assign(map, "other", Value.Record(mapOf("x" to string("1"), "y" to string("1")))) }
        peers.sync()
        assertEquals(mapOf("x" to Atom.Str("a"), "y" to Atom.Str("b")), a.snapshot[value].fields)
        edit(a) { delete(value) }
        edit(peers.replicas[1]) { assign(value, "x", string("deleted edit")) }
        peers.sync()
        assertFalse("key" in (a.snapshot.genericValue(map) as GenericValue.Object).fields)
        edit(a) { assign(map, "key", Value.Record(mapOf("x" to string("fresh"), "y" to string("fresh")))) }
        peers.sync()
        assertNotEquals(value, (a.snapshot[map].fields["key"] as Atom.Instance).id)
        assertEquals(GenericValue.Deleted(value), a.snapshot.genericValue(value))
    }
    @Test fun enumsAndOptionalSwitchValidateWholeBatchAndGenericViewNeedsNoRenderer() {
        val peers = Peers()
        val a = peers.replicas[0]
        val enumeration = Type.Enum(setOf("red", "blue"))
        var scalar = ""
        var sum = ""
        edit(a) {
            scalar = create(enumeration, string("red"))
            sum = create(Type.Sum(mapOf("None" to null, "Some" to enumeration)), Value.Variant("None"))
        }
        val before = a.snapshot
        assertFailsWith<IllegalArgumentException> { edit(a) { assign(scalar, "value", string("blue")); switch(sum, Value.Variant("Some", string("green"))) } }
        assertEquals(before, a.snapshot)
        edit(a) { switch(sum, Value.Variant("Some", string("blue"))) }
        peers.sync()
        assertTrue(a.snapshot.genericValue(sum) is GenericValue.Object)
        peers.replicas.forEach { assertEquals(a.snapshot, it.snapshot) }
    }
    @Test fun causallyLaterInsertUsesRequestedPositionRegardlessOfAuthorKeyOrder() {
        val peers = Peers()
        val a = peers.replicas.maxBy { it.author }
        val b = peers.replicas.minBy { it.author }
        var text = ""
        var list = ""
        edit(a) { text = create(Type.Scalar.TEXT, Value.Text("AB")); list = create(Type.Sequence(Type.Scalar.STRING), Value.Sequence(listOf(string("A"), string("B")))) }
        peers.sync()
        edit(b) { editText(text, 1, 0, "X"); insert(list, b.snapshot[list].children.first(), string("X")) }
        peers.sync()
        assertEquals("AXB", a.snapshot[text].text)
        assertEquals(listOf("A", "X", "B"), a.snapshot[list].children.map { (a.snapshot[it].fields["value"] as Atom.Str).value })
    }

    @Test fun seededStructuralCommandsConvergeAndPreserveTypes() {
        for (seed in testSeeds(8)) {
            val random = Random(seed)
            val peers = Peers(seed)
            val a = peers.replicas[0]
            val recordType = Type.Record(mapOf("x" to Type.Scalar.STRING, "y" to Type.Scalar.STRING))
            val record = Value.Record(mapOf("x" to string("initial"), "y" to string("initial")))
            var text = ""; var list = ""; var map = ""; var sum = ""; var product = ""
            edit(a) {
                text = create(Type.Scalar.TEXT, Value.Text("🌍\n"))
                list = create(Type.Sequence(Type.Scalar.STRING), Value.Sequence(emptyList()))
                map = create(Type.MapOf(recordType), Value.MapEntries(emptyMap()))
                sum = create(Type.Sum(mapOf("None" to null, "Some" to recordType)), Value.Variant("None"))
                product = create(recordType, record)
            }
            peers.sync()
            val schedule = mutableListOf<String>()
            try {
                repeat(45) { step ->
                    val replica = peers.replicas[random.nextInt(3)]
                    edit(replica) {
                        when (random.nextInt(6)) {
                            0 -> { val size = replica.snapshot[text].textPositions.size; val at = random.nextInt(size + 1); editText(text, at, if (at < size && random.nextBoolean()) 1 else 0, if (random.nextBoolean()) "λ" else "🌍") }
                            1 -> { val children = replica.snapshot[list].children; insert(list, children.randomOrNull(random), string("$step")) }
                            2 -> { val children = replica.snapshot[list].children; if (children.isNotEmpty()) delete(children.random(random)) else insert(list, null, string("$step")) }
                            3 -> assign(map, "key${random.nextInt(3)}", Value.Record(mapOf("x" to string("$step"), "y" to string(replica.author))))
                            4 -> switch(sum, if (random.nextBoolean()) Value.Variant("None") else Value.Variant("Some", record))
                            else -> assign(product, if (random.nextBoolean()) "x" else "y", string("$step"))
                        }
                    }
                    val source = peers.replicas[random.nextInt(3)]
                    val batch = source.history.random(random)
                    schedule.add("${batch.batch.id}->${replica.author}")
                    replica.ingest(batch, peers.keys[0].author)
                    if (step % 15 == 14) {
                        peers.sync()
                        peers.replicas.forEach { r ->
                            assertEquals(a.snapshot, r.snapshot)
                            val types = TypeSystem(r.snapshot)
                            r.snapshot.objects.values.forEach(types::validateObject)
                        }
                    }
                }
                val history = peers.replicas.flatMap { it.history }.distinct()
                peers.keys.forEach { key ->
                    val rebuilt = ReplicaEngine("edits", key.author, peers.keys[0].author, key, MemoryJournal(), TypedMutationValidator())
                    (history + history).shuffled(random).forEach { rebuilt.ingest(it, peers.keys[0].author) }
                    assertEquals(a.snapshot, rebuilt.snapshot)
                }
            } catch (failure: Throwable) {
                throw AssertionError("seed=$seed schedule=$schedule history=${peers.replicas.flatMap { it.history }.distinct().map { it.batch }}", failure)
            }
        }
    }

    @Test fun recursiveNominalValuesAreFiniteAndReferencesStayExplicit() {
        val peers = Peers()
        val a = peers.replicas[0]
        val node = a.nextId(0)
        val optional = Type.Sum(mapOf("None" to null, "Some" to Type.Ref(Type.Named(node))))
        a.commit(listOf(TypeEncoding.publish(node, TypeDefinition(emptyList(), false, Type.Record(mapOf("next" to optional))))))
        var first = ""; var second = ""
        edit(a) { first = create(Type.Named(node), Value.Record(mapOf("next" to Value.Variant("None")))) }
        edit(a) { second = create(Type.Named(node), Value.Record(mapOf("next" to Value.Variant("Some", Value.Atomic(Atom.Ref(first)))))) }
        peers.sync()
        assertEquals(TypeEncoding.encode(Type.Named(node)), a.snapshot[first].type)
        assertNotEquals(first, second)
        peers.replicas.forEach { assertEquals(a.snapshot.genericValue(second), it.snapshot.genericValue(second)) }
    }

    @Test fun independentValidEditsMayBreakCrossFieldBusinessPredicates() {
        val peers = Peers()
        val a = peers.replicas[0]
        var product = ""
        edit(a) { product = create(Type.Record(mapOf("x" to Type.Scalar.NUMBER, "y" to Type.Scalar.NUMBER)), Value.Record(mapOf("x" to Value.Atomic(Atom.Number(0.0)), "y" to Value.Atomic(Atom.Number(0.0))))) }
        peers.sync()
        edit(a) { assign(product, "x", Value.Atomic(Atom.Number(1.0))) }
        edit(peers.replicas[1]) { assign(product, "y", Value.Atomic(Atom.Number(1.0))) }
        peers.sync()
        // Each proposal separately satisfies x + y <= 1; structural typing does not serialize that predicate.
        assertEquals(2.0, a.snapshot[product].fields.values.sumOf { (it as Atom.Number).value })
        peers.replicas.forEach { assertEquals(a.snapshot, it.snapshot) }
    }

    @Test fun mixedEmbeddedAndListContainmentCyclesResolveWithoutSharingInstances() {
        val peers = Peers()
        val a = peers.replicas[0]
        val node = a.nextId(0)
        a.commit(listOf(TypeEncoding.publish(node, TypeDefinition(emptyList(), false, Type.Record(mapOf("children" to Type.Sequence(Type.Named(node))))))))
        var left = ""; var right = ""
        edit(a) {
            left = create(Type.Named(node), Value.Record(mapOf("children" to Value.Sequence(emptyList()))))
            right = create(Type.Named(node), Value.Record(mapOf("children" to Value.Sequence(emptyList()))))
        }
        peers.sync()
        val leftChildren = (a.snapshot[left].fields["children"] as Atom.Instance).id
        val rightChildren = (a.snapshot[right].fields["children"] as Atom.Instance).id
        assertFailsWith<IllegalArgumentException> { edit(a) { move(left, leftChildren) } }
        assertFailsWith<IllegalArgumentException> { edit(a) { move(leftChildren, rightChildren) } }
        assertFailsWith<IllegalArgumentException> { a.commit(listOf(Operation.Assign(left, "children", Atom.Instance(rightChildren)))) }
        edit(a) { move(left, rightChildren) }
        edit(peers.replicas[1]) { move(right, leftChildren) }
        peers.sync()
        val snapshot = a.snapshot
        assertFalse(snapshot[left].parent == rightChildren && snapshot[right].parent == leftChildren)
        assertTrue(snapshot[left].parent == null || snapshot[right].parent == null)
        peers.replicas.forEach {
            assertEquals(snapshot, it.snapshot)
            assertEquals(snapshot.genericValue(left), it.snapshot.genericValue(left))
            assertEquals(snapshot.genericValue(right), it.snapshot.genericValue(right))
        }
    }

    @Test fun activeVariantNestedFieldsMergeIndependentlyAndSameFieldUsesRegisterOrder() {
        val peers = Peers()
        val a = peers.replicas[0]
        val b = peers.replicas[1]
        val payloadType = Type.Record(mapOf("x" to Type.Scalar.STRING, "y" to Type.Scalar.STRING, "shared" to Type.Scalar.STRING))
        var sum = ""
        edit(a) { sum = create(Type.Sum(mapOf("Active" to payloadType)), Value.Variant("Active", Value.Record(mapOf("x" to string("0"), "y" to string("0"), "shared" to string("0"))))) }
        peers.sync()
        val payload = (a.snapshot[sum].fields["value"] as Atom.Variant).payload!!
        edit(a) { assign(payload, "x", string("a")); assign(payload, "shared", string("a")) }
        edit(b) { assign(payload, "y", string("b")); assign(payload, "shared", string("b")) }
        peers.sync()
        assertEquals(Atom.Str("a"), a.snapshot[payload].fields["x"])
        assertEquals(Atom.Str("b"), a.snapshot[payload].fields["y"])
        assertEquals(Atom.Str(if (a.author > b.author) "a" else "b"), a.snapshot[payload].fields["shared"])
        peers.replicas.forEach { assertEquals(a.snapshot, it.snapshot) }
    }

    @Test fun nestedProductListSumReplacementsAtTwoDepthsStayCoherent() {
        val peers = Peers()
        val a = peers.replicas[0]
        val leaf = Type.Record(mapOf("label" to Type.Scalar.STRING))
        val sumType = Type.Sum(mapOf("Items" to Type.Sequence(leaf), "Empty" to null))
        val outerType = Type.Record(mapOf("content" to sumType))
        fun items(text: String): Value = Value.Variant("Items", Value.Sequence(listOf(Value.Record(mapOf("label" to string(text))))))
        var outer = ""
        edit(a) { outer = create(outerType, Value.Record(mapOf("content" to items("old")))) }
        peers.sync()
        val oldSum = (a.snapshot[outer].fields["content"] as Atom.Instance).id
        edit(a) { assign(outer, "content", items("new outer")) }
        edit(peers.replicas[1]) { switch(oldSum, Value.Variant("Empty")) }
        peers.sync()
        val selected = (a.snapshot[outer].fields["content"] as Atom.Instance).id
        assertNotEquals(oldSum, selected)
        assertEquals(Atom.Variant("Empty", null), a.snapshot[oldSum].fields["value"])
        val active = a.snapshot[selected].fields["value"] as Atom.Variant
        assertEquals("Items", active.tag)
        val leafId = a.snapshot[active.payload!!].children.single()
        assertEquals(Atom.Str("new outer"), a.snapshot[leafId].fields["label"])
        peers.replicas.forEach { assertEquals(a.snapshot.genericValue(outer), it.snapshot.genericValue(outer)) }
    }

    @Test fun missingPublishedTypeBuffersWholeParentAndChildBatch() {
        val peers = Peers()
        val a = peers.replicas[0]
        val b = peers.replicas[1]
        val definition = a.nextId(0)
        val published = a.commit(listOf(TypeEncoding.publish(definition, TypeDefinition(emptyList(), false, Type.Record(mapOf("name" to Type.Scalar.STRING))))))
        val before = b.snapshot
        val values = edit(a) { create(Type.Sequence(Type.Named(definition)), Value.Sequence(listOf(Value.Record(mapOf("name" to string("child")))))) }
        assertEquals(IngestResult.BUFFERED, b.ingest(values, peers.keys[0].author))
        assertEquals(before, b.snapshot)
        b.ingest(published, peers.keys[0].author)
        assertTrue(b.pendingIds.isEmpty())
        assertEquals(a.snapshot, b.snapshot)
    }

    @Test fun sameAnchorConcurrentInsertionsHaveExactStableOrder() {
        val peers = Peers()
        val a = peers.replicas[0]
        val b = peers.replicas[1]
        var list = ""
        edit(a) { list = create(Type.Sequence(Type.Scalar.STRING), Value.Sequence(listOf(string("anchor")))) }
        peers.sync()
        val anchor = a.snapshot[list].children.single()
        var first = ""; var second = ""
        edit(a) { first = insert(list, anchor, string("a")) }
        edit(b) { second = insert(list, anchor, string("b")) }
        peers.sync()
        val expected = listOf(anchor) + if (a.author > b.author) listOf(first, second) else listOf(second, first)
        peers.replicas.forEach { assertEquals(expected, it.snapshot[list].children) }
    }

    @Test fun deletingOrdinaryContentHidesConcurrentEditsAndRestorationDoesNotRepairOldReference() {
        val peers = Peers()
        val a = peers.replicas[0]
        val type = Type.Record(mapOf("name" to Type.Scalar.STRING))
        var document = ""; var reference = ""
        edit(a) { document = create(type, Value.Record(mapOf("name" to string("original")))) }
        edit(a) { reference = create(Type.Ref(type), Value.Atomic(Atom.Ref(document))) }
        peers.sync()
        edit(a) { delete(document) }
        edit(peers.replicas[1]) { assign(document, "name", string("concurrent hidden edit")) }
        peers.sync()
        assertEquals(GenericValue.Deleted(document), a.snapshot.genericValue(document))
        var restored = ""
        edit(a) { restored = create(type, Value.Record(mapOf("name" to string("restored")))) }
        peers.sync()
        assertNotEquals(document, restored)
        val view = a.snapshot.genericValue(reference) as GenericValue.Object
        assertEquals(GenericValue.Reference(document, ReferenceStatus.DELETED), view.fields["value"])
        peers.replicas.forEach { assertEquals(a.snapshot, it.snapshot) }
    }

    @Test fun longTextUsesScalarOffsetsWithoutRecursiveTraversal() {
        val peers = Peers()
        val a = peers.replicas[0]
        val content = "a🌍\n".repeat(7000)
        var text = ""
        edit(a) { text = create(Type.Scalar.TEXT, Value.Text(content)) }
        assertEquals(21000, a.snapshot[text].textPositions.size)
        edit(a) { editText(text, 1, 1, "β") }
        assertEquals("aβ\n" + content.drop(4), a.snapshot[text].text)
        peers.sync()
        peers.replicas.forEach { assertEquals(a.snapshot[text], it.snapshot[text]) }
    }
}
