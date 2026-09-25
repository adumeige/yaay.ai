package ai.yaay.crdt

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

class HttpFaultTest {
    private class Pair : AutoCloseable {
        val root = Files.createTempDirectory("yaay-http-fault-")
        val validator = MutationValidator { _, _, _, _ -> }
        val a = DurableReplica.open(root.resolve("a"), "fault", validator = validator)
        val b = DurableReplica.open(root.resolve("b"), "fault", a.author, validator = validator)
        val ea = SyncEndpoint(a, BlobStore(root.resolve("a/chunks")))
        val eb = SyncEndpoint(b, BlobStore(root.resolve("b/chunks")))
        init {
            a.commit { _, _ -> listOf(Operation.Membership(b.author, true)) }
            SyncClient(eb, a.author, ExchangeConnection(ea::respond)).exchange()
        }
        override fun close() { a.close(); b.close(); root.toFile().deleteRecursively() }
    }
    private fun uri(port: Int): URI = URI("http://127.0.0.1:$port/sync")

    @Test fun bothPeersInitiateHttpSessionsConcurrentlyWithoutDuplicateCommits() = Pair().use { pair ->
        pair.a.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "a")) }
        pair.b.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "b")) }
        HttpSyncServer(pair.ea).use { sa -> HttpSyncServer(pair.eb).use { sb ->
            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val jobs = listOf(
                    executor.submit { start.await(); SyncClient(pair.ea, pair.b.author, HttpExchangeConnection(uri(sb.address.port))).exchange() },
                    executor.submit { start.await(); SyncClient(pair.eb, pair.a.author, HttpExchangeConnection(uri(sa.address.port))).exchange() },
                )
                start.countDown()
                jobs.forEach { it.get(15, TimeUnit.SECONDS) }
                assertEquals(pair.a.snapshot(), pair.b.snapshot())
                assertEquals(3, pair.a.history().size)
                assertEquals(3, pair.b.history().size)
            } finally { executor.shutdownNow() }
        } }
    }

    @Test fun signedStaleInventoryIsCorrectedBySubsequentReconciliation() = Pair().use { pair ->
        pair.a.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        val stale = ExchangeConnection { bytes ->
            val request = Envelope.verify(bytes)
            val inventory = java.io.DataInputStream(request.payload.inputStream()).readUTF() == "inventory"
            if (inventory) Envelope(pair.a.author, pair.a.workspace, "response", request.nonce, Wire.write { writeInt(0) }).sign(pair.a)
            else pair.ea.respond(bytes)
        }
        SyncClient(pair.eb, pair.a.author, stale).exchange()
        assertNotEquals(pair.a.snapshot(), pair.b.snapshot())
        HttpSyncServer(pair.ea).use { server -> SyncClient(pair.eb, pair.a.author, HttpExchangeConnection(uri(server.address.port))).exchange() }
        assertEquals(pair.a.snapshot(), pair.b.snapshot())
    }

    @Test fun truncatedRealHttpBodyAndDelayedBodyLeaveStateUntouchedAndRetryWorks() = Pair().use { pair ->
        pair.a.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        val before = pair.b.snapshot()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
        server.executor = executor
        server.createContext("/sync") { exchange ->
            exchange.use {
                val request = exchange.requestBody.readAllBytes()
                val response = pair.ea.respond(request)
                exchange.sendResponseHeaders(200, response.size.toLong())
                if (exchange.requestHeaders.getFirst("X-Test-Delay") == "yes") {
                    entered.countDown(); release.await(5, TimeUnit.SECONDS)
                }
                try { exchange.responseBody.write(response.copyOf(response.size / 2)) } catch (_: java.io.IOException) { }
            }
        }
        server.start()
        try {
            assertFails { SyncClient(pair.eb, pair.a.author, HttpExchangeConnection(uri(server.address.port))).exchange() }
            assertEquals(before, pair.b.snapshot())
            // A separate endpoint sends headers promptly but holds the body beyond the client deadline.
            server.removeContext("/sync")
            server.createContext("/sync") { exchange -> exchange.use {
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, 100)
                entered.countDown(); release.await(5, TimeUnit.SECONDS)
            } }
            assertFailsWith<TimeoutException> { HttpExchangeConnection(uri(server.address.port), Duration.ofMillis(250)).request(byteArrayOf(1)) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(before, pair.b.snapshot())
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
        HttpSyncServer(pair.ea).use { working -> SyncClient(pair.eb, pair.a.author, HttpExchangeConnection(uri(working.address.port))).exchange() }
        assertEquals(pair.a.snapshot(), pair.b.snapshot())
    }

    @Test fun durableUnsynchronizedCommitRetransmitsAfterReplicaRestart() = Pair().use { pair ->
        val committed = pair.a.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "offline")) }
        val author = pair.a.author
        pair.a.close()
        DurableReplica.open(pair.root.resolve("a"), "fault", validator = pair.validator).use { reopened ->
            assertEquals(author, reopened.author)
            val endpoint = SyncEndpoint(reopened, pair.ea.blobs)
            HttpSyncServer(pair.eb).use { server -> SyncClient(endpoint, pair.b.author, HttpExchangeConnection(uri(server.address.port))).exchange() }
            assertTrue(pair.b.history().any { it == committed })
            assertEquals(reopened.snapshot(), pair.b.snapshot())
        }
    }
}
