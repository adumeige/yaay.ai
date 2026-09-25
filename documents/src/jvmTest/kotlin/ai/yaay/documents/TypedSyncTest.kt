package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import java.net.URI
import java.nio.file.Files
import kotlin.test.*

class TypedSyncTest {
    @Test fun typedTextListAndSumConvergeOverBothRealAdaptersAndRestart() {
        for (directory in listOf(false, true)) {
            val root = Files.createTempDirectory("yaay-typed-sync-")
            val validator = TypedMutationValidator()
            val a = DurableReplica.open(root.resolve("a"), "typed", validator = validator)
            val b = DurableReplica.open(root.resolve("b"), "typed", a.author, validator = validator)
            val ea = SyncEndpoint(a, BlobStore(root.resolve("a/blobs")))
            val eb = SyncEndpoint(b, BlobStore(root.resolve("b/blobs")))
            val server = if (!directory) HttpSyncServer(ea) else null
            try {
                a.commit { _, _ -> listOf(Operation.Membership(b.author, true)) }
                val creation = a.commit { id, _ -> listOf(
                    Operation.Create(id(0), TypeEncoding.encode(Type.Scalar.TEXT), Shape.TEXT),
                    Operation.Create(id(1), TypeEncoding.encode(Type.Sequence(Type.Scalar.STRING)), Shape.LIST),
                    Operation.Create(id(2), TypeEncoding.encode(Type.Sum(mapOf("None" to null, "Some" to Type.Scalar.STRING))), Shape.SUM, mapOf("value" to Atom.Variant("None", null))),
                ) }
                val text = (creation.batch.operations[0] as Operation.Create).id
                val list = (creation.batch.operations[1] as Operation.Create).id
                val sum = (creation.batch.operations[2] as Operation.Create).id
                fun exchange() {
                    if (directory) repeat(3) {
                        listOf(ea,eb).forEach { DirectorySync(it, root.resolve("share")).publish() }
                        listOf(ea,eb).forEach { DirectorySync(it, root.resolve("share")).poll() }
                    } else SyncClient(eb, a.author, HttpExchangeConnection(URI("http://127.0.0.1:${server!!.address.port}/sync"))).exchange()
                }
                exchange()
                a.commit { id, _ -> listOf(
                    Operation.Create(id(0), TypeEncoding.encode(Type.Scalar.STRING), Shape.REGISTER, mapOf("value" to Atom.Str("alpha"))),
                    Operation.Place(id(0), list), Operation.EditText(text, null, "α🌍"),
                    Operation.Create(id(3), TypeEncoding.encode(Type.Scalar.STRING), Shape.REGISTER, mapOf("value" to Atom.Str("selected payload"))),
                    Operation.Assign(sum, "value", Atom.Variant("Some", id(3))),
                ) }
                b.commit { id, _ -> listOf(
                    Operation.Create(id(0), TypeEncoding.encode(Type.Scalar.STRING), Shape.REGISTER, mapOf("value" to Atom.Str("beta"))),
                    Operation.Place(id(0), list), Operation.EditText(text, null, "β\n"),
                ) }
                exchange()
                val expected = a.snapshot()
                assertEquals(expected, b.snapshot())
                assertEquals(2, expected[list].children.size)
                assertTrue("α🌍" in expected[text].text && "β\n" in expected[text].text)
                assertEquals("Some", (expected[sum].fields["value"] as Atom.Variant).tag)
                b.close()
                DurableReplica.open(root.resolve("b"), "typed", a.author, validator = validator).use { reopened -> assertEquals(expected, reopened.snapshot()) }
            } finally { server?.close(); a.close(); b.close(); root.toFile().deleteRecursively() }
        }
    }
}
