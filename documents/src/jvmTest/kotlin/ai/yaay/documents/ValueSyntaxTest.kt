package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import kotlin.test.*

class ValueSyntaxTest {
    private val key = JvmBatchCrypto.generate()
    private val r = ReplicaEngine("values", key.author, key.author, key, MemoryJournal(), TypedMutationValidator())
    private val blob = Atom.Blob("a".repeat(64), 5, listOf("b".repeat(64)))
    init {
        val declarations = TypeSyntax.parseDeclarations("""
            type Person = {
                name: String, bio: Text, age: Number, active: Boolean, avatar: Blob, tags: List<String>,
                scores: Map<Number>, grid: Map<{ x: Number, y: Number }, String>, "odd key": String,
                manager: Maybe<Ref<Person>>, status: Status, color: enum(red, "dark blue"), maybe: Maybe<Number>,
            }
            type Status = Active | Suspended { reason: Text } | Archived(Number)
            type Maybe<T> = Some(T) | None
        """.trimIndent(), TypeCatalog(r.snapshot), r::nextId)
        r.commit(declarations.mapIndexed { i, d -> TypeEncoding.publish(r.nextId(i), d.definition, d.name) })
    }
    private fun syntax() = ValueSyntax(r.snapshot) { path -> assertEquals("avatar.png", path); blob }
    private val person get() = TypeSyntax.parseType("Person", TypeCatalog(r.snapshot))
    private fun create(text: String): Pair<String, Value> {
        var created = ""
        var parsed: Value? = null
        r.commit(TypedEdit(r::nextId, r.snapshot).apply { val s = syntax(); parsed = s.parse(text, person); created = create(person, parsed!!) }.operations)
        return created to parsed!!
    }
    private val ada = """
        { name: "Ada \"the first\"\n", bio: "Countess 🌍", age: -1.5e3, active: true, avatar: file("avatar.png"),
          tags: ["math", "poetry"], scores: { algebra: 1, "number theory": -0.0 },
          grid: { { x: 1, y: 2 }: "b", { x: 0, y: 0 }: "a" }, "odd key": "",
          manager: MANAGER, status: Suspended { reason: "leave" }, color: "dark blue", maybe: Some(42) }
    """.trimIndent()

    @Test fun everyShapeRoundTripsThroughRenderAndParse() {
        val (first, _) = create(ada.replace("MANAGER", "None"))
        val (second, parsed) = create(ada.replace("MANAGER", "Some(ref(@${ObjectHandles.short(first)}))"))
        val rendered = syntax().render(second)
        assertEquals(parsed, syntax().parse(rendered, person), rendered)
        assertTrue(rendered.contains("manager: Some(ref(@${ObjectHandles.short(first)}))"), rendered)
        assertTrue(rendered.contains("color: \"dark blue\""), rendered)
        assertTrue(rendered.contains("scores: { algebra: 1, \"number theory\": -0.0 }"), rendered)
        assertTrue(rendered.contains("grid: { { x: 0, y: 0 }: \"a\", { x: 1, y: 2 }: \"b\" }"), rendered)
        assertTrue(rendered.contains("avatar: blob(\"${blob.hash}\", 5, [\"${blob.chunks.single()}\"])"), rendered)
    }

    @Test fun parseErrorsExplainWhatWasExpectedAndWhere() {
        fun failure(text: String): String = assertFailsWith<IllegalArgumentException> { syntax().parse(text, TypeSyntax.parseType("Status", TypeCatalog(r.snapshot))) }.message.orEmpty()
        assertTrue(failure("Paused").startsWith("Expected one of Active, Archived, Suspended at 1:1"), failure("Paused"))
        assertTrue(failure("Archived").startsWith("Variant Archived needs a payload"))
        assertTrue(failure("Active(1)").startsWith("Variant Active has no payload"))
        assertTrue(failure("Suspended {}").startsWith("Missing field(s) reason"))
        assertTrue(failure("Suspended { reason: \"x\", why: 1 }").startsWith("Unknown field why"))
        assertTrue(failure("Archived(\"soon\")").startsWith("Expected a number at 1:10"))
        assertTrue(failure("Active extra").startsWith("Unexpected input"))
        val color = assertFailsWith<IllegalArgumentException> { syntax().parse("purple", Type.Enum(setOf("red", "blue"))) }
        assertTrue(color.message.orEmpty().startsWith("Expected one of blue, red"))
    }

    @Test fun pathsStepThroughFieldsVariantsMapKeysAndListIndices() {
        val (id, _) = create(ada.replace("MANAGER", "None"))
        val s = syntax()
        val handle = ObjectHandles.short(id)
        assertEquals("\"poetry\"", s.render(s.locate("$handle.tags.1")))
        assertEquals("{ reason: \"leave\" }", s.render(s.locate("$handle.status.Suspended")))
        assertEquals("1", s.render(s.locate("$handle.scores.algebra")))
        assertEquals(s.locate("$handle.maybe.Some") to "value", s.field("$handle.maybe.Some"))
        assertEquals(id to "name", s.field("$handle.name"))
        assertEquals(id to "tags", s.field("$handle.tags"))
        assertEquals(listOf(id), s.roots())
        assertTrue(assertFailsWith<IllegalArgumentException> { s.locate("$handle.status.Active") }.message.orEmpty().contains("active variant is Suspended"))
        assertTrue(assertFailsWith<IllegalArgumentException> { s.locate("$handle.name") }.message.orEmpty().contains("plain value"))
        assertTrue(assertFailsWith<IllegalArgumentException> { s.locate("$handle.tags.9") }.message.orEmpty().contains("No list item 9"))
    }
}
