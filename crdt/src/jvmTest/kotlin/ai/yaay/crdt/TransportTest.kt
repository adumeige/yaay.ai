package ai.yaay.crdt

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TransportTest {
    private class World : AutoCloseable {
        val root = Files.createTempDirectory("yaay-sync-")
        val validation = MutationValidator { _, _, _, _ -> }
        val a = endpoint("a")
        val b = endpoint("b", a.replica.author)
        val c = endpoint("c", a.replica.author)
        val share = root.resolve("share")
        init { a.replica.commit { _, _ -> listOf(Operation.Membership(b.replica.author, true), Operation.Membership(c.replica.author, true)) } }
        fun endpoint(name: String, founder: String? = null): SyncEndpoint {
            val dir = root.resolve(name)
            return SyncEndpoint(DurableReplica.open(dir, "sync", founder, validator = validation), BlobStore(dir.resolve("chunks")))
        }
        override fun close() { listOf(a,b,c).forEach { it.replica.close() }; root.toFile().deleteRecursively() }
    }
    @Test fun sameConcurrentContentAndBinaryTraceAcrossMemoryHttpAndDirectory() {
        for (transport in listOf("memory", "http", "directory")) World().use { w ->
            val servers = if (transport == "http") listOf(HttpSyncServer(w.a), HttpSyncServer(w.b)) else emptyList()
            try {
                fun exchange() {
                    when (transport) {
                        "directory" -> repeat(3) {
                            listOf(w.a,w.b).forEach { DirectorySync(it, w.share).publish() }
                            listOf(w.a,w.b).forEach { DirectorySync(it, w.share).poll() }
                        }
                        else -> {
                            val connection = if (transport == "http") HttpExchangeConnection(URI("http://127.0.0.1:${servers[0].address.port}/sync")) else ExchangeConnection(w.a::respond)
                            SyncClient(w.b, w.a.replica.author, connection).exchange()
                        }
                    }
                }
                exchange()
                val content = ByteArray(BlobStore.CHUNK_SIZE + 37) { (it % 251).toByte() }
                val blob = w.a.blobs.put(content.inputStream())
                val initial = w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "blob", Shape.REGISTER, mapOf("value" to blob))) }
                w.b.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "peer🌍")) }
                exchange(); exchange()
                assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot(), transport)
                assertContentEquals(content, w.b.blobs.open(blob)!!.use { it.readAllBytes() }, transport)
                assertEquals(1, w.b.replica.history().count { it.batch.id == initial.batch.id })
            } finally { servers.forEach { it.close() } }
        }
    }
    @Test fun httpJoinViaRelayAfterOriginalAuthorDisappearsAndInterruptedSessionRetries() = World().use { w ->
        val direct = ExchangeConnection(w.a::respond)
        SyncClient(w.b, w.a.replica.author, direct).exchange()
        repeat(3) { w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "retained-$it")) } }
        var calls = 0
        val interrupted = ExchangeConnection { bytes -> if (++calls == 3) throw java.io.IOException("connection lost") else direct.request(bytes) }
        assertFailsWith<java.io.IOException> { SyncClient(w.b, w.a.replica.author, interrupted).exchange() }
        SyncClient(w.b, w.a.replica.author, direct).exchange()
        val expected = w.a.replica.snapshot()
        w.a.replica.close()
        HttpSyncServer(w.b).use { server ->
            SyncClient(w.c, w.b.replica.author, HttpExchangeConnection(URI("http://127.0.0.1:${server.address.port}/sync"))).exchange()
        }
        assertEquals(expected, w.c.replica.snapshot())
    }
    @Test fun partialAndCorruptDirectoryCandidatesNeverExposePartialState() = World().use { w ->
        val publisher = DirectorySync(w.a, w.share)
        val receiver = DirectorySync(w.b, w.share)
        publisher.publish(); receiver.poll()
        val before = w.b.replica.snapshot()
        val batch = w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "whole")) }
        publisher.publish()
        val hash = BatchCodec.hash(BatchCodec.encode(batch))
        val area = w.share.resolve(BatchCodec.hash("sync".toByteArray())).resolve(BatchCodec.hash(w.a.replica.author.toByteArray()))
        val payload = area.resolve("batch-$hash.payload")
        val complete = Files.readAllBytes(payload)
        Files.write(payload, complete.copyOf(complete.size / 2))
        assertTrue(receiver.poll().deferred > 0)
        assertEquals(before, w.b.replica.snapshot())
        Files.write(area.resolve("orphan.tmp"), complete)
        Files.write(payload, complete)
        receiver.poll()
        assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
        Files.write(area.resolve("batch-$hash.ready"), byteArrayOf(1,2,3))
        assertTrue(receiver.poll().deferred > 0)
        assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
    }
    @Test fun directoryRelayBootstrapsNewPeerWithoutOriginalPublisherAndDeduplicates() = World().use { w ->
        SyncClient(w.b, w.a.replica.author, ExchangeConnection(w.a::respond)).exchange()
        val batch = w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "relayed")) }
        w.b.receiveBatch(BatchCodec.encode(batch), w.a.replica.author)
        val expected = w.a.replica.snapshot()
        w.a.replica.close()
        val publisher = DirectorySync(w.b, w.share)
        publisher.publish()
        val receiver = DirectorySync(w.c, w.share)
        repeat(3) { receiver.poll() }
        assertEquals(expected, w.c.replica.snapshot())
        DirectorySync(w.c, w.share).publish()
        repeat(2) { publisher.poll(); receiver.poll() }
        assertEquals(1, w.c.replica.history().count { it.batch.id == batch.batch.id })
    }

    @Test fun changedTransportEnvelopeAndNeverAdmittedPublisherAreRejected() = World().use { w ->
        val payload = Wire.write { writeUTF("inventory"); writeUTF("") }
        val valid = Envelope(w.b.replica.author, "sync", "request", "nonce", payload).sign(w.b.replica)
        val tampered = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { w.a.respond(tampered) }
        val stranger = w.endpoint("stranger", w.a.replica.author)
        try {
            val request = Envelope(stranger.replica.author, "sync", "request", "nonce", payload).sign(stranger.replica)
            assertFailsWith<ProtocolFault> { w.a.respond(request) }
        } finally { stranger.replica.close() }
        assertEquals(1, w.a.replica.history().size)
    }

    @Test fun invalidSignaturesAndUnsupportedBatchesUseSameAtomicPathOnBothAdapters() {
        for (directory in listOf(false, true)) World().use { w ->
            val created = w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "supported")) }
            SyncClient(w.b, w.a.replica.author, ExchangeConnection(w.a::respond)).exchange()
            val before = w.b.replica.snapshot()
            val target = (created.batch.operations.first() as Operation.Create).id
            val counter = created.batch.id.counter + 1
            val logical = Batch(w.a.replica.workspace, 1, BatchId(w.a.replica.author, counter), Frontier(created.batch.vector.counters + (w.a.replica.author to counter)), listOf(Operation.EditText(target, null, "must not appear"), Operation.Unknown(987, listOf(3, 4))))
            fun sign(batch: Batch): SignedBatch {
                val bytes = BatchCodec.logical(batch)
                return SignedBatch(batch, BatchCodec.hash(bytes), w.a.replica.signTransport(bytes).toList())
            }
            val unknown = sign(logical)
            val invalid = unknown.copy(signature = unknown.signature.toMutableList().also { it[0] = (it[0].toInt() xor 1).toByte() })
            val server = if (directory) null else HttpSyncServer(w.b)
            try {
                fun deliver(batch: SignedBatch, valid: Boolean) {
                    val bytes = BatchCodec.encode(batch)
                    if (directory) {
                        val area = w.share.resolve(BatchCodec.hash(w.a.replica.workspace.toByteArray())).resolve(BatchCodec.hash(w.a.replica.author.toByteArray()))
                        Files.createDirectories(area)
                        val hash = BatchCodec.hash(bytes)
                        val name = "batch-$hash"
                        val ready = Envelope(w.a.replica.author, w.a.replica.workspace, "publication", name, Wire.write { writeUTF("batch"); writeUTF(hash); writeInt(bytes.size) }).sign(w.a.replica)
                        Files.write(area.resolve("$name.payload"), bytes)
                        Files.write(area.resolve("$name.ready"), ready)
                        val report = DirectorySync(w.b, w.share).poll()
                        if (!valid) assertTrue(report.diagnostics.isNotEmpty())
                    } else {
                        val request = Envelope(w.a.replica.author, w.a.replica.workspace, "request", "fault-test", Wire.write { writeUTF("batch-put"); with(Wire) { bytes(bytes) } }).sign(w.a.replica)
                        val connection = HttpExchangeConnection(URI("http://127.0.0.1:${server!!.address.port}/sync"))
                        if (valid) assertEquals("fault-test", Envelope.verify(connection.request(request)).nonce)
                        else assertFailsWith<IllegalArgumentException> { connection.request(request) }
                    }
                }
                deliver(invalid, false)
                assertFalse(w.b.replica.readOnly())
                assertEquals(before, w.b.replica.snapshot())
                deliver(unknown, true)
                assertTrue(w.b.replica.readOnly())
                assertEquals(before, w.b.replica.snapshot())
                val dependent = sign(logical.copy(id = BatchId(w.a.replica.author, counter + 1), vector = Frontier(logical.vector.counters + (w.a.replica.author to counter + 1)), operations = listOf(Operation.EditText(target, null, "dependent"))))
                deliver(dependent, true)
                assertEquals(before, w.b.replica.snapshot())
            } finally { server?.close() }
            w.b.replica.close()
            DurableReplica.open(w.root.resolve("b"), "sync", w.a.replica.author, validator = w.validation).use {
                assertTrue(it.readOnly())
                assertEquals(before, it.snapshot())
            }
        }
    }

    @Test fun newPeerReplaysHistoryBeyondAnInventoryPageOnBothAdapters() {
        for (directory in listOf(false, true)) World().use { w ->
            repeat(130) { index -> w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "history-$index")) } }
            val content = "retained blob".toByteArray()
            val blob = w.a.blobs.put(content.inputStream())
            w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "blob", Shape.REGISTER, mapOf("value" to blob))) }
            if (directory) {
                DirectorySync(w.a, w.share).publish()
                repeat(3) { DirectorySync(w.b, w.share).poll() }
            } else HttpSyncServer(w.a).use { server ->
                SyncClient(w.b, w.a.replica.author, HttpExchangeConnection(URI("http://127.0.0.1:${server.address.port}/sync"))).exchange()
            }
            assertEquals(132, w.b.replica.history().size)
            assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
            assertContentEquals(content, w.b.blobs.open(blob)!!.use { it.readAllBytes() })
        }
    }

    @Test fun controlledReverseDependencyDeliveryBuffersThroughBothActualAdapters() {
        for (directory in listOf(false, true)) World().use { w ->
            SyncClient(w.b, w.a.replica.author, ExchangeConnection(w.a::respond)).exchange()
            val before = w.b.replica.snapshot()
            val prerequisite = w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
            val objectId = (prerequisite.batch.operations.first() as Operation.Create).id
            val dependent = w.a.replica.commit { _, _ -> listOf(Operation.EditText(objectId, null, "causal text")) }
            val server = if (directory) null else HttpSyncServer(w.b)
            try {
                fun deliver(batch: SignedBatch) {
                    val payload = BatchCodec.encode(batch)
                    if (directory) {
                        val area = w.share.resolve(BatchCodec.hash(w.a.replica.workspace.toByteArray())).resolve(BatchCodec.hash(w.a.replica.author.toByteArray()))
                        Files.createDirectories(area)
                        val hash = BatchCodec.hash(payload)
                        val ready = Envelope(w.a.replica.author, w.a.replica.workspace, "publication", "batch-$hash", Wire.write { writeUTF("batch"); writeUTF(hash); writeInt(payload.size) }).sign(w.a.replica)
                        Files.write(area.resolve("batch-$hash.payload"), payload)
                        Files.write(area.resolve("batch-$hash.ready"), ready)
                        DirectorySync(w.b, w.share).poll()
                    } else {
                        val request = Envelope(w.a.replica.author, w.a.replica.workspace, "request", "reverse-dependency", Wire.write { writeUTF("batch-put"); with(Wire) { bytes(payload) } }).sign(w.a.replica)
                        val response = HttpExchangeConnection(URI("http://127.0.0.1:${server!!.address.port}/sync")).request(request)
                        assertEquals("reverse-dependency", Envelope.verify(response).nonce)
                    }
                }
                deliver(dependent)
                assertEquals(before, w.b.replica.snapshot())
                deliver(prerequisite)
                assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
                assertEquals("causal text", w.b.replica.snapshot()[objectId].text)
            } finally { server?.close() }
        }
    }

    @Test fun interruptedHttpChunkSessionKeepsConsumerPendingAndRepairsCorruptLocalChunks() = World().use { w ->
        val content = ByteArray(BlobStore.CHUNK_SIZE + 123) { (it % 251).toByte() }
        val blob = w.a.blobs.put(content.inputStream())
        w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "blob", Shape.REGISTER, mapOf("value" to blob))) }
        HttpSyncServer(w.a).use { server ->
            val connection = HttpExchangeConnection(URI("http://127.0.0.1:${server.address.port}/sync"))
            var fetched = 0
            val interrupted = ExchangeConnection { bytes ->
                val request = Envelope.verify(bytes)
                val command = java.io.DataInputStream(request.payload.inputStream()).readUTF()
                if (command == "chunk-get" && ++fetched == 2) throw java.io.IOException("disconnect between chunks")
                connection.request(bytes)
            }
            assertFailsWith<java.io.IOException> { SyncClient(w.b, w.a.replica.author, interrupted).exchange() }
            assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
            assertNull(w.b.blobs.open(blob))
            assertNotNull(w.b.blobs.chunk(blob.chunks.first()))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val waiting = executor.submit<ByteArray> { w.b.blobs.await(blob, Duration.ofSeconds(15)).use { it.readAllBytes() } }
                assertFalse(waiting.isDone)
                SyncClient(w.b, w.a.replica.author, connection).exchange()
                assertContentEquals(content, waiting.get(15, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
            Files.write(w.root.resolve("b/chunks").resolve(blob.chunks.first()), byteArrayOf(9))
            assertFailsWith<IllegalArgumentException> { w.b.blobs.open(blob) }
            SyncClient(w.b, w.a.replica.author, connection).exchange()
            assertContentEquals(content, w.b.blobs.open(blob)!!.use { it.readAllBytes() })
        }
    }

    @Test fun blobConsumerWaitsUntilEveryChunkPassesVerification() {
        val root = Files.createTempDirectory("yaay-blob-")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val source = BlobStore(root.resolve("source"))
            val target = BlobStore(root.resolve("target"))
            val bytes = ByteArray(BlobStore.CHUNK_SIZE + 1) { (it % 253).toByte() }
            val blob = source.put(bytes.inputStream())
            assertNull(target.open(blob))
            assertFailsWith<IllegalArgumentException> { target.putChunk(blob.chunks[0], byteArrayOf(0)) }
            val result = executor.submit<ByteArray> { target.await(blob, Duration.ofSeconds(10)).use { it.readAllBytes() } }
            target.putChunk(blob.chunks[0], source.chunk(blob.chunks[0])!!)
            assertFalse(result.isDone)
            target.putChunk(blob.chunks[1], source.chunk(blob.chunks[1])!!)
            assertContentEquals(bytes, result.get(10, TimeUnit.SECONDS))
            assertFailsWith<IllegalArgumentException> { target.open(blob.copy(hash = "0".repeat(64))) }
        } finally { executor.shutdownNow(); root.toFile().deleteRecursively() }
    }
    @Test fun excludedDirectHttpPeerIsRefusedButAdmittedRelayPreservesAuthorship() = World().use { w ->
        SyncClient(w.b, w.a.replica.author, ExchangeConnection(w.a::respond)).exchange()
        SyncClient(w.c, w.a.replica.author, ExchangeConnection(w.a::respond)).exchange()
        val edit = w.b.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        w.c.receiveBatch(BatchCodec.encode(edit), w.b.replica.author)
        w.a.replica.commit { _, _ -> listOf(Operation.Membership(w.b.replica.author, false)) }
        HttpSyncServer(w.a).use { server ->
            assertFailsWith<IllegalArgumentException> { SyncClient(w.b, w.a.replica.author, HttpExchangeConnection(URI("http://127.0.0.1:${server.address.port}/sync"))).exchange() }
            SyncClient(w.c, w.a.replica.author, HttpExchangeConnection(URI("http://127.0.0.1:${server.address.port}/sync"))).exchange()
        }
        assertTrue(w.a.replica.history().any { it == edit })
    }
}
