package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import kotlin.test.*

class MapKeyTest {
    private fun replica(): ReplicaEngine {
        val crypto = JvmBatchCrypto.generate()
        return ReplicaEngine("keys", crypto.author, crypto.author, crypto, MemoryJournal(), TypedMutationValidator())
    }
    private fun edit(r: ReplicaEngine, action: TypedEdit.() -> Unit) { r.commit(TypedEdit(r::nextId, r.snapshot).apply(action).operations) }
    private fun key(value: String): KeyValue = KeyValue.Atomic(Atom.Str(value))
    private fun value(value: String): Value = Value.Atomic(Atom.Str(value))
    @Test fun genericMapKeySubstitutionAndRecordKeyCanonicalization() {
        val r = replica()
        val definition = r.nextId(0)
        r.commit(listOf(TypeEncoding.publish(definition, TypeDefinition(listOf("K", "V"), true, Type.MapOf(Type.Parameter("V"), Type.Parameter("K"))))))
        val keyType = Type.Record(mapOf("name" to Type.Scalar.STRING, "active" to Type.Scalar.BOOLEAN))
        val actual = Type.Named(definition, listOf(keyType, Type.Scalar.STRING))
        val first = KeyValue.Record(linkedMapOf("name" to key("Ada"), "active" to KeyValue.Atomic(Atom.Bool(true))))
        val reordered = KeyValue.Record(linkedMapOf("active" to KeyValue.Atomic(Atom.Bool(true)), "name" to key("Ada")))
        var map = ""
        edit(r) { map = create(actual, Value.KeyedEntries(mapOf(first to value("first")))) }
        edit(r) { put(map, reordered, value("replacement")) }
        assertEquals(1, r.snapshot[map].fields.size)
        val field = KeyEncoding.toField(TypeSystem(r.snapshot), keyType, first)
        val selected = (r.snapshot[map].fields[field] as Atom.Instance).id
        assertEquals(Atom.Str("replacement"), r.snapshot[selected].fields["value"])
        assertEquals(first, KeyEncoding.fromField(TypeSystem(r.snapshot), keyType, field))
        val before = r.snapshot
        assertFailsWith<IllegalArgumentException> { edit(r) { put(map, key("wrong type"), value("bad")) } }
        assertEquals(before, r.snapshot)
    }
    @Test fun numbersHaveCanonicalIdentityAndInvalidRawKeyCannotEnterIncomingBatch() {
        val r = replica()
        var map = ""
        edit(r) { map = create(Type.MapOf(Type.Scalar.STRING, Type.Scalar.NUMBER), Value.KeyedEntries(mapOf(KeyValue.Atomic(Atom.Number(-0.0)) to value("zero")))) }
        edit(r) { put(map, KeyValue.Atomic(Atom.Number(0.0)), value("same key")) }
        assertEquals(1, r.snapshot[map].fields.size)
        val before = r.snapshot
        val child = r.nextId(0)
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(Operation.Create(child, TypeEncoding.encode(Type.Scalar.STRING), Shape.REGISTER, mapOf("value" to Atom.Str("bad"))), Operation.Assign(map, "not a canonical number", Atom.Instance(child)))) }
        assertEquals(before, r.snapshot)
        assertFailsWith<IllegalArgumentException> { KeyEncoding.encode(KeyValue.Atomic(Atom.Number(Double.NaN))) }
    }
    @Test fun binaryContentUsedAsTypedKeySynchronizesThroughBothAdapters() {
        for (directory in listOf(false, true)) {
            val root = java.nio.file.Files.createTempDirectory("yaay-key-content-")
            val validator = TypedMutationValidator()
            val a = DurableReplica.open(root.resolve("a"), "key-content", validator = validator)
            val b = DurableReplica.open(root.resolve("b"), "key-content", a.author, validator = validator)
            try {
                val ea = typedSyncEndpoint(a, BlobStore(root.resolve("a/chunks")))
                val eb = typedSyncEndpoint(b, BlobStore(root.resolve("b/chunks")))
                a.commit { _, _ -> listOf(Operation.Membership(b.author, true)) }
                val content = "immutable key content".toByteArray()
                val blob = ea.blobs.put(content.inputStream())
                a.commit { id, snapshot -> TypedEdit(id, snapshot).apply {
                    create(Type.MapOf(Type.Scalar.STRING, Type.Scalar.BLOB), Value.KeyedEntries(mapOf(KeyValue.Atomic(blob) to value("metadata"))))
                }.operations }
                if (directory) {
                    DirectorySync(ea, root.resolve("share")).publish()
                    repeat(3) { DirectorySync(eb, root.resolve("share")).poll() }
                } else HttpSyncServer(ea).use { server ->
                    SyncClient(eb, a.author, HttpExchangeConnection(java.net.URI("http://127.0.0.1:${server.address.port}/sync"))).exchange()
                }
                assertEquals(a.snapshot(), b.snapshot())
                assertContentEquals(content, eb.blobs.open(blob)!!.use { it.readAllBytes() })
            } finally { a.close(); b.close(); root.toFile().deleteRecursively() }
        }
    }

    @Test fun finiteAlgebraicAndReferenceKeysRoundTripWithoutImplicitDereference() {
        val r = replica()
        var referenced = ""
        edit(r) { referenced = create(Type.Scalar.STRING, value("target")) }
        val types = TypeSystem(r.snapshot)
        val cases = listOf(
            Type.Ref(Type.Scalar.STRING) to KeyValue.Atomic(Atom.Ref(referenced)),
            Type.Enum(setOf("a", "b")) to key("a"),
            Type.Scalar.TEXT to key("🌍\n"),
            Type.Sequence(Type.Scalar.BOOLEAN) to KeyValue.Sequence(listOf(KeyValue.Atomic(Atom.Bool(true)))),
            Type.Sum(mapOf("None" to null, "Some" to Type.Scalar.STRING)) to KeyValue.Variant("Some", key("text")),
            Type.MapOf(Type.Scalar.STRING, Type.Scalar.BOOLEAN) to KeyValue.MapEntries(mapOf(KeyValue.Atomic(Atom.Bool(false)) to key("nested"))),
        )
        cases.forEach { (type, key) -> assertEquals(key, KeyEncoding.fromField(types, type, KeyEncoding.toField(types, type, key))) }
        assertFailsWith<IllegalArgumentException> { KeyEncoding.toField(types, Type.Ref(Type.Scalar.NUMBER), KeyValue.Atomic(Atom.Ref(referenced))) }
    }
}
