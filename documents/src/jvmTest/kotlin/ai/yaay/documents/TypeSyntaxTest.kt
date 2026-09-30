package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import kotlin.test.*

class TypeSyntaxTest {
    private fun replica(): ReplicaEngine {
        val key = JvmBatchCrypto.generate()
        return ReplicaEngine("syntax", key.author, key.author, key, MemoryJournal(), TypedMutationValidator())
    }
    private fun define(r: ReplicaEngine, text: String): Map<String, String> {
        val declarations = TypeSyntax.parseDeclarations(text, TypeCatalog(r.snapshot), r::nextId)
        val ids = declarations.indices.map { r.nextId(it) }
        r.commit(declarations.mapIndexed { i, d -> TypeEncoding.publish(ids[i], d.definition, d.name) })
        return declarations.map { it.name }.zip(ids).toMap()
    }
    private val schema = """
        # Forward, recursive and mutual references resolve within one file.
        type Person = {
            name: String, bio: Text, age: Number, active: Boolean, avatar: Blob,
            tags: List<String>, scores: Map<Number>, "odd key": String,
            manager: Ref<Person>, status: Status, color: enum(red, green, "dark blue"),
        }
        type Status = Active | Suspended { reason: Text } | Archived(Number)
        type Pair<A, B> = { first: A, second: B }
        alias Names = List<String>
        type Tree = { label: String, children: List<Tree> }
        type Only = | Single
        type Keyed = Map<{ x: Number, y: Number }, String>
        type Box<T> = { item: T, pairs: Pair<T, Names>, choice: Left(T) | Right }
    """.trimIndent()

    @Test fun everyConstructParsesToTheIntendedTypeAndPublishes() {
        val r = replica()
        val ids = define(r, schema)
        val definitions = TypeCatalog(r.snapshot).entries.associate { it.name to it.definition }
        val person = definitions.getValue("Person").body as Type.Record
        assertEquals(Type.Scalar.TEXT, person.fields["bio"])
        assertEquals(Type.Sequence(Type.Scalar.STRING), person.fields["tags"])
        assertEquals(Type.MapOf(Type.Scalar.NUMBER), person.fields["scores"])
        assertEquals(Type.Scalar.STRING, person.fields["odd key"])
        assertEquals(Type.Ref(Type.Named(ids.getValue("Person"))), person.fields["manager"])
        assertEquals(Type.Named(ids.getValue("Status")), person.fields["status"])
        assertEquals(Type.Enum(setOf("red", "green", "dark blue")), person.fields["color"])
        assertEquals(Type.Sum(mapOf("Active" to null, "Suspended" to Type.Record(mapOf("reason" to Type.Scalar.TEXT)), "Archived" to Type.Scalar.NUMBER)), definitions["Status"]?.body)
        assertEquals(TypeDefinition(listOf("A", "B"), false, Type.Record(mapOf("first" to Type.Parameter("A"), "second" to Type.Parameter("B")))), definitions["Pair"])
        assertTrue(definitions.getValue("Names").alias)
        assertEquals(Type.Sequence(Type.Named(ids.getValue("Tree"))), (definitions.getValue("Tree").body as Type.Record).fields["children"])
        assertEquals(Type.Sum(mapOf("Single" to null)), definitions["Only"]?.body)
        assertEquals(Type.MapOf(Type.Scalar.STRING, Type.Record(mapOf("x" to Type.Scalar.NUMBER, "y" to Type.Scalar.NUMBER))), definitions["Keyed"]?.body)
        val box = definitions.getValue("Box").body as Type.Record
        assertEquals(Type.Named(ids.getValue("Pair"), listOf(Type.Parameter("T"), Type.Named(ids.getValue("Names")))), box.fields["pairs"])
    }

    @Test fun renderedDeclarationsParseBackToTheSameDefinitions() {
        val r = replica()
        define(r, schema)
        val catalog = TypeCatalog(r.snapshot)
        for (entry in catalog.entries) {
            val rendered = TypeSyntax.render(TypeDeclaration(entry.name!!, entry.definition), catalog::label)
            // Re-declare under a probe name so the body's references resolve to the published definitions.
            val probe = rendered.replaceFirst(" ${entry.name}", " Probe")
            assertEquals(entry.definition, TypeSyntax.parseDeclarations(probe, catalog) { "probe" }.single().definition, rendered)
        }
    }

    @Test fun errorsNameTheProblemAndItsPosition() {
        val r = replica()
        fun failure(text: String): String = assertFailsWith<IllegalArgumentException> { define(r, text) }.message.orEmpty()
        assertTrue(failure("type A = { x: Nope }").startsWith("Unknown type Nope at 1:15"), failure("type A = { x: Nope }"))
        assertTrue(failure("type A = { x: String, x: Number }").startsWith("Field x is declared twice"))
        assertTrue(failure("type A = String\ntype A = Number").startsWith("Type A is declared twice at 2:6"))
        assertTrue(failure("type P<T> = { v: T }\ntype Q = P").startsWith("Expected 1 type argument(s), found 0"))
        assertTrue(failure("type List = String").startsWith("'List' is reserved"))
        assertTrue(failure("type A = X | X").startsWith("Variant X is declared twice"))
        assertTrue(failure("type A = { s: \"open }").startsWith("Unterminated string"))
        assertTrue(failure("").startsWith("No type declarations found"))
        assertTrue(failure("alias A = B\nalias B = A").startsWith("Alias cycle"))
        assertTrue(r.history.isEmpty())
    }

    @Test fun sameNamedVersionsNeedAnExplicitHandleAndNamesAreValidated() {
        val r = replica()
        val first = define(r, "type Person = { name: String }").getValue("Person")
        val second = define(r, "type Person = { name: String, email: String }").getValue("Person")
        val catalog = TypeCatalog(r.snapshot)
        val ambiguous = assertFailsWith<IllegalArgumentException> { TypeSyntax.parseType("Person", catalog) }
        assertTrue(ambiguous.message.orEmpty().contains("2 versions"), ambiguous.message)
        assertEquals(Type.Named(first), TypeSyntax.parseType("Person@${ObjectHandles.short(first)}", catalog))
        assertEquals(Type.Named(second), TypeSyntax.parseType("@${ObjectHandles.short(second)}", catalog))
        assertEquals("Person@${ObjectHandles.short(second)}", catalog.label(second))
        // Names are display metadata: identity stays the object ID, and bad names never publish.
        for (bad in listOf("List", "has space", "")) {
            assertFailsWith<IllegalArgumentException>(bad) { r.commit(listOf(TypeEncoding.publish(r.nextId(0), TypeDefinition(emptyList(), false, Type.Scalar.STRING), bad))) }
        }
        assertEquals(2, TypeCatalog(r.snapshot).entries.size)
    }
}
