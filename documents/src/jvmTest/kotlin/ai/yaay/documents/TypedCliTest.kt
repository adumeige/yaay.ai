package ai.yaay.documents

import ai.yaay.crdt.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import kotlin.test.*

/** Two peers driven only through headless-peer commands: typed definitions and edits over HTTP and a shared folder. */
class TypedCliTest {
    private val root = Files.createTempDirectory("yaay-typed-cli-")
    private val share = root.resolve("share")
    private fun peer(store: String, vararg args: String): String {
        val bytes = ByteArrayOutputStream()
        PeerMain.execute(listOf(root.resolve(store).toString(), "demo") + args, PrintStream(bytes, true, Charsets.UTF_8)) { error("no stdin") }
        return bytes.toString(Charsets.UTF_8)
    }
    private fun String.value(key: String): String = lines().first { it.startsWith("$key=") }.substringAfter("=")
    private fun show(store: String, path: String): String = peer(store, "show", path).trim().split('\t').last()

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun typedDefinitionsAndEditsConvergeOverHttpAndASharedFolder() {
        val a = peer("a", "init").value("author")
        val b = peer("b", "init", a).value("author")
        peer("a", "admit", b)
        val schema = root.resolve("schema.yaay")
        Files.writeString(schema, """
            type Person = { name: String, bio: Text, tags: List<String>, status: Status }
            type Status = Active | Suspended { reason: Text }
        """.trimIndent())
        val defined = peer("a", "define", schema.toString())
        assertTrue(defined.lines().any { it.startsWith("Person=") } && defined.lines().any { it.startsWith("Status=") }, defined)
        val person = peer("a", "create", "Person", """{ name: "Ada", bio: "Mathematician", tags: ["math"], status: Active }""").value("object")

        // HTTP: B pulls the definitions and the person from A.
        DurableReplica.open(root.resolve("a"), "demo", validator = ai.yaay.documents.types.TypedMutationValidator()).use { replica ->
            HttpSyncServer(typedSyncEndpoint(replica, BlobStore(root.resolve("a/chunks")))).use { server ->
                peer("b", "sync-http", a, URI("http://127.0.0.1:${server.address.port}/sync").toString())
            }
        }
        assertTrue(peer("b", "types").contains("type Person = { bio: Text, name: String, status: Status, tags: List<String> }"))
        assertEquals("""{ bio: "Mathematician", name: "Ada", status: Active, tags: ["math"] }""", show("b", person))

        // B edits a field, the list, the variant and the collaborative text; A edits the list concurrently.
        peer("b", "set", "$person.name", "\"Ada Lovelace\"")
        peer("b", "insert", "$person.tags", "\"poetry\"")
        peer("b", "switch", "$person.status", """Suspended { reason: "sabbatical" }""")
        peer("b", "edit-text", "$person.bio", "0", "0", "Countess and ")
        peer("a", "insert", "$person.tags", "\"logic\"")

        // Shared folder, both directions.
        peer("b", "publish", share.toString())
        peer("a", "poll", share.toString())
        peer("a", "publish", share.toString())
        peer("b", "poll", share.toString())

        val converged = show("a", person)
        assertEquals(converged, show("b", person))
        assertTrue(converged.startsWith("""{ bio: "Countess and Mathematician", name: "Ada Lovelace", status: Suspended { reason: "sabbatical" }, tags: ["""), converged)
        // Both concurrent appends survive after the original item, in one deterministic order.
        val tags = show("a", "$person.tags")
        assertTrue(tags == """["math", "logic", "poetry"]""" || tags == """["math", "poetry", "logic"]""", tags)
        assertEquals(peer("a", "state"), peer("b", "state"))
    }

    @Test fun workspaceTreeBuiltOnOnePeerIsReorganizedOnAnotherAndConverges() {
        val a = peer("a", "init").value("author")
        val b = peer("b", "init", a).value("author")
        peer("a", "admit", b)
        val schema = root.resolve("schema.yaay")
        Files.writeString(schema, "type Person = { name: String, bio: Text }")
        peer("a", "define", schema.toString())
        val projects = peer("a", "mkdir", "Projects").value("object")
        val ada = peer("a", "new", "Ada", "Person", """{ name: "Ada", bio: "Mathematician" }""", projects).value("object")
        val scratch = peer("a", "new", "Scratch", "Text", "\"todo\"").value("object")
        assertEquals(listOf("Projects/\t$projects", "  Ada\t$ada\tPerson", "Scratch\t$scratch\tText"), peer("a", "tree").trim().lines())

        DurableReplica.open(root.resolve("a"), "demo", validator = ai.yaay.documents.types.TypedMutationValidator()).use { replica ->
            HttpSyncServer(typedSyncEndpoint(replica, BlobStore(root.resolve("a/chunks")))).use { server ->
                peer("b", "sync-http", a, URI("http://127.0.0.1:${server.address.port}/sync").toString())
            }
        }
        // B reorganizes and edits; A adds a document concurrently.
        val archive = peer("b", "mkdir", "Archive").value("object")
        peer("b", "move", ada, archive)
        peer("b", "rename", ada, "Ada Lovelace")
        peer("b", "set", "$ada.Document.content.name", "\"Ada Lovelace\"")
        peer("a", "new", "Grace", "Person", """{ name: "Grace", bio: "Admiral" }""", projects)

        peer("b", "publish", share.toString())
        peer("a", "poll", share.toString())
        peer("a", "publish", share.toString())
        peer("b", "poll", share.toString())

        val tree = peer("a", "tree")
        assertEquals(tree, peer("b", "tree"))
        val lines = tree.trim().lines().map { it.substringBefore('\t') }
        assertEquals(listOf("Archive/", "  Ada Lovelace", "Projects/", "  Grace", "Scratch"), lines, tree)
        assertEquals("""{ bio: "Mathematician", name: "Ada Lovelace" }""", show("a", "$ada.Document.content"))
        assertEquals(peer("a", "state"), peer("b", "state"))
    }

    @Test fun invalidTypedInputIsRejectedWithoutCommitting() {
        peer("a", "init")
        val schema = root.resolve("schema.yaay")
        Files.writeString(schema, "type Person = { name: String, age: Number }")
        peer("a", "define", schema.toString())
        val before = peer("a", "info").value("batches")
        for (args in listOf(
            listOf("create", "Person", """{ name: "Ada" }"""),
            listOf("create", "Person", """{ name: "Ada", age: "old" }"""),
            listOf("create", "Nobody", "{}"),
        )) {
            val failure = assertFailsWith<IllegalArgumentException>(args.toString()) { peer("a", *args.toTypedArray()) }
            assertTrue(failure.message.orEmpty().contains(" at 1:"), failure.message)
        }
        val person = peer("a", "create", "Person", """{ name: "Ada", age: 36 }""").value("object")
        assertFailsWith<IllegalArgumentException> { peer("a", "set", "$person.age", "\"older\"") }
        assertFailsWith<IllegalArgumentException> { peer("a", "set", "$person.email", "\"a@b\"") }
        assertEquals(before.toInt() + 1, peer("a", "info").value("batches").toInt())
        assertEquals("""{ age: 36, name: "Ada" }""", show("a", person))
    }
}
