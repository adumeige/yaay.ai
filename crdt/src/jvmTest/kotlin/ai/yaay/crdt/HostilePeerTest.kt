package ai.yaay.crdt

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.*

/** The initiating client must distrust every response: the peer it dials may be hostile or impersonated. */
class HostilePeerTest {
    private class World : AutoCloseable {
        val root = Files.createTempDirectory("yaay-hostile-")
        val validation = MutationValidator { _, _, _, _ -> }
        val a = endpoint("a")
        val b = endpoint("b", a.replica.author)
        val c = endpoint("c", a.replica.author)
        init {
            a.replica.commit { _, _ -> listOf(Operation.Membership(b.replica.author, true), Operation.Membership(c.replica.author, true)) }
            listOf(b, c).forEach { SyncClient(it, a.replica.author, ExchangeConnection(a::respond)).exchange() }
        }
        fun endpoint(name: String, founder: String? = null): SyncEndpoint {
            val dir = root.resolve(name)
            return SyncEndpoint(DurableReplica.open(dir, "hostile", founder, validator = validation), BlobStore(dir.resolve("chunks")))
        }
        fun text(endpoint: SyncEndpoint): SignedBatch = endpoint.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        override fun close() { listOf(a, b, c).forEach { it.replica.close() }; root.toFile().deleteRecursively() }
    }
    private val sessionMismatch = "Response does not match pinned peer/session"
    private fun command(request: ByteArray): List<String> = DataInputStream(ByteArrayInputStream(Envelope.verify(request).payload)).run {
        val name = readUTF()
        if (name == "inventory" || name == "batch-get") listOf(name, readUTF()) else listOf(name)
    }
    /** A correctly signed response from [endpoint] carrying an arbitrary payload for this request's session. */
    private fun respondAs(endpoint: SyncEndpoint, request: ByteArray, payload: ByteArray): ByteArray =
        Envelope(endpoint.replica.author, endpoint.replica.workspace, "response", Envelope.verify(request).nonce, payload).sign(endpoint.replica)

    @Test fun replayedResponseFromAnEarlierRequestIsRejected() = World().use { w ->
        repeat(2) { w.text(w.a) }
        val before = w.b.replica.history()
        var recorded: ByteArray? = null
        val replaying = ExchangeConnection { request -> recorded ?: w.a.respond(request).also { recorded = it } }
        val failure = assertFailsWith<IllegalArgumentException> { SyncClient(w.b, w.a.replica.author, replaying).exchange() }
        assertEquals(sessionMismatch, failure.message)
        assertEquals(before, w.b.replica.history())
    }

    @Test fun validResponseFromAnotherAdmittedPeerIsRejected() = World().use { w ->
        w.text(w.c)
        val before = w.b.replica.history()
        val failure = assertFailsWith<IllegalArgumentException> { SyncClient(w.b, w.a.replica.author, ExchangeConnection(w.c::respond)).exchange() }
        assertEquals(sessionMismatch, failure.message)
        assertEquals(before, w.b.replica.history())
    }

    @Test fun batchWhoseBytesDoNotMatchTheRequestedHashIsRejected() = World().use { w ->
        val missing = List(2) { BatchCodec.encode(w.text(w.a)) }.associateBy { BatchCodec.hash(it) }
        val before = w.b.replica.history()
        val swapping = ExchangeConnection { request ->
            val command = command(request)
            if (command[0] != "batch-get") w.a.respond(request)
            else respondAs(w.a, request, Wire.write { with(Wire) { bytes(missing.entries.first { it.key != command[1] }.value) } })
        }
        assertFailsWith<IllegalArgumentException> { SyncClient(w.b, w.a.replica.author, swapping).exchange() }
        assertEquals(before, w.b.replica.history())
    }

    @Test fun inventoryPagesThatStallOrAreUnsortedAreRejected() {
        for (stalled in listOf(true, false)) World().use { w ->
            repeat(3) { w.text(w.a) }
            val hashes = w.a.batches().keys.sorted()
            val page = if (stalled) hashes else hashes.reversed()
            var inventoryCalls = 0
            val hostile = ExchangeConnection { request ->
                if (command(request)[0] != "inventory") w.a.respond(request) else {
                    check(++inventoryCalls < 20) { "Client kept paging a stalled inventory" }
                    val served = if (stalled || inventoryCalls == 1) page else emptyList()
                    respondAs(w.a, request, Wire.write { writeInt(served.size); served.forEach { writeUTF(it) } })
                }
            }
            val before = w.b.replica.history()
            assertFailsWith<IllegalArgumentException>("stalled=$stalled") { SyncClient(w.b, w.a.replica.author, hostile).exchange() }
            assertEquals(before, w.b.replica.history())
        }
    }

    @Test fun oversizedHttpResponseBodyIsAbandonedAtTheLimit() {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4)
        server.executor = executor
        server.createContext("/sync") { exchange ->
            exchange.use {
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, 0)
                val chunk = ByteArray(64 * 1024)
                var sent = 0L
                try { while (sent <= Wire.LIMIT) { exchange.responseBody.write(chunk); sent += chunk.size } } catch (_: java.io.IOException) { }
            }
        }
        server.start()
        try {
            val failure = assertFails { HttpExchangeConnection(URI("http://127.0.0.1:${server.address.port}/sync")).request(byteArrayOf(1)) }
            assertTrue(generateSequence(failure) { it.cause }.any { it is ProtocolFault && it.message == "Oversized HTTP body" }, failure.toString())
        } finally { server.stop(0); executor.shutdownNow() }
    }
}
