package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import kotlin.test.*

class TypesTest {
    private fun replica(): ReplicaEngine {
        val key = JvmBatchCrypto.generate()
        return ReplicaEngine("types", key.author, key.author, key, MemoryJournal(), TypedMutationValidator())
    }
    private fun publish(r: ReplicaEngine, type: Type, alias: Boolean = false, parameters: List<String> = emptyList()): String {
        val id = r.nextId(0)
        r.commit(listOf(TypeEncoding.publish(id, TypeDefinition(parameters, alias, type))))
        return id
    }
    @Test fun nominalIdentitiesAndPinnedAliasesRemainDistinctAcrossVersions() {
        val r = replica()
        val body = Type.Record(mapOf("name" to Type.Scalar.STRING))
        val a = publish(r, body)
        val b = publish(r, body)
        val alias = publish(r, Type.Named(a), true)
        val newAlias = publish(r, Type.Named(b), true)
        val types = TypeSystem(r.snapshot)
        assertFalse(types.assignable(Type.Named(a), Type.Named(b)))
        assertTrue(types.assignable(Type.Named(alias), Type.Named(a)))
        assertFalse(types.assignable(Type.Named(newAlias), Type.Named(a)))
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(Operation.Assign(a, "value", Atom.Str(TypeEncoding.encode(TypeDefinition(emptyList(), false, Type.Scalar.STRING)))))) }
        val id = r.nextId(0)
        r.commit(listOf(Operation.Create(id, TypeEncoding.encode(Type.Named(a)), Shape.RECORD, mapOf("name" to Atom.Str("Ada")))))
        assertEquals(TypeEncoding.encode(Type.Named(a)), r.snapshot[id].type)
    }
    @Test fun invalidFieldsWrongNominalReferencesAndMixedBatchesNeverCommit() {
        val r = replica()
        val a = publish(r, Type.Record(mapOf("name" to Type.Scalar.STRING)))
        val b = publish(r, Type.Record(mapOf("name" to Type.Scalar.STRING)))
        val id = r.nextId(0)
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(Operation.Create(id, TypeEncoding.encode(Type.Named(a)), Shape.RECORD))) }
        r.commit(listOf(Operation.Create(id, TypeEncoding.encode(Type.Named(a)), Shape.RECORD, mapOf("name" to Atom.Str("valid")))))
        val before = r.snapshot
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(Operation.Assign(id, "name", Atom.Str("staged")), Operation.Assign(id, "extra", Atom.Bool(true)))) }
        assertEquals(before, r.snapshot)
        val ref = r.nextId(0)
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(Operation.Create(ref, TypeEncoding.encode(Type.Ref(Type.Named(b))), Shape.REGISTER, mapOf("value" to Atom.Ref(id))))) }
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(Operation.Create(ref, TypeEncoding.encode(Type.Ref(Type.Named(a))), Shape.REGISTER, mapOf("value" to Atom.Instance(id))))) }
        r.commit(listOf(Operation.Create(ref, TypeEncoding.encode(Type.Ref(Type.Named(a))), Shape.REGISTER, mapOf("value" to Atom.Ref(id)))))
        r.commit(listOf(Operation.Delete(id)))
        assertTrue(r.snapshot[id].deleted)
        assertEquals(Atom.Ref(id), r.snapshot[ref].fields["value"])
    }
    @Test fun genericAliasesRecursiveNominalTypesAndCoherentOptionalValues() {
        val r = replica()
        val generic = publish(r, Type.Record(mapOf("item" to Type.Parameter("T"))), parameters = listOf("T"))
        val alias = publish(r, Type.Named(generic, listOf(Type.Parameter("U"))), true, listOf("U"))
        val box = r.nextId(0)
        r.commit(listOf(Operation.Create(box, TypeEncoding.encode(Type.Named(alias, listOf(Type.Scalar.STRING))), Shape.RECORD, mapOf("item" to Atom.Str("boxed")))))
        val recursive = r.nextId(0)
        r.commit(listOf(TypeEncoding.publish(recursive, TypeDefinition(emptyList(), false, Type.Record(mapOf("next" to Type.Sum(mapOf("None" to null, "Some" to Type.Ref(Type.Named(recursive))))))))))
        val optionalType = Type.Sum(mapOf("None" to null, "Some" to Type.Scalar.STRING))
        val optional = r.nextId(0)
        r.commit(listOf(Operation.Create(optional, TypeEncoding.encode(optionalType), Shape.SUM, mapOf("value" to Atom.Variant("None", null)))))
        val scalar = r.nextId(0)
        r.commit(listOf(Operation.Create(scalar, TypeEncoding.encode(Type.Scalar.STRING), Shape.REGISTER, mapOf("value" to Atom.Str("payload"))), Operation.Assign(optional, "value", Atom.Variant("Some", scalar))))
        assertEquals(Atom.Variant("Some", scalar), r.snapshot[optional].fields["value"])
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(Operation.Assign(optional, "value", Atom.Variant("None", scalar)))) }
    }
    @Test fun aliasOnlyCycleRejectedAndIncomingTypesUseTheSameBoundary() {
        val r = replica()
        val a = r.nextId(0)
        val b = r.nextId(1)
        assertFailsWith<IllegalArgumentException> { r.commit(listOf(TypeEncoding.publish(a, TypeDefinition(emptyList(), true, Type.Named(b))), TypeEncoding.publish(b, TypeDefinition(emptyList(), true, Type.Named(a))))) }
        assertTrue(r.history.isEmpty())
        val remoteKey = JvmBatchCrypto.generate()
        r.commit(listOf(Operation.Membership(remoteKey.author, true)))
        val id = BatchId(remoteKey.author, 1)
        val invalid = remoteKey.sign(Batch("types", 1, id, Frontier(r.snapshot.frontier.counters + (remoteKey.author to 1L)), listOf(Operation.Create(OpId(id, 0).stableId(), TypeEncoding.encode(Type.Scalar.BOOLEAN), Shape.REGISTER, mapOf("value" to Atom.Str("wrong"))))))
        val before = r.snapshot
        assertFailsWith<IllegalArgumentException> { r.ingest(invalid, remoteKey.author) }
        assertEquals(before, r.snapshot)
    }
}
