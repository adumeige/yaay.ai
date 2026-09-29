package ai.yaay.crdt

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import org.junit.Assume
import kotlin.test.*

class DirectoryFaultTest {
    private class World : AutoCloseable {
        val root = Files.createTempDirectory("yaay-directory-fault-")
        val share = root.resolve("share")
        val validator = MutationValidator { _, _, _, _ -> }
        val a = endpoint("a")
        val b = endpoint("b", a.replica.author)
        val c = endpoint("c", a.replica.author)
        init {
            a.replica.commit { _, _ -> listOf(Operation.Membership(b.replica.author, true), Operation.Membership(c.replica.author, true)) }
            listOf(b,c).forEach { SyncClient(it, a.replica.author, ExchangeConnection(a::respond)).exchange() }
        }
        fun endpoint(name: String, founder: String? = null): SyncEndpoint {
            val path = root.resolve(name)
            return SyncEndpoint(DurableReplica.open(path, "directory-fault", founder, validator = validator), BlobStore(path.resolve("chunks")))
        }
        fun area(endpoint: SyncEndpoint): Path = share.resolve(BatchCodec.hash(endpoint.replica.workspace.toByteArray())).resolve(BatchCodec.hash(endpoint.replica.author.toByteArray()))
        override fun close() { listOf(a,b,c).forEach { it.replica.close() }; root.toFile().deleteRecursively() }
    }
    @Test fun excludedPublisherIsIgnoredWhileAdmittedRelayKeepsOriginalBatch() = World().use { w ->
        val edit = w.b.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        w.c.receiveBatch(BatchCodec.encode(edit), w.b.replica.author)
        w.a.replica.commit { _, _ -> listOf(Operation.Membership(w.b.replica.author, false)) }
        DirectorySync(w.b, w.share).publish()
        DirectorySync(w.a, w.share).poll()
        assertFalse(w.a.replica.history().any { it.batch.id == edit.batch.id })
        DirectorySync(w.c, w.share).publish()
        DirectorySync(w.a, w.share).poll()
        assertTrue(w.a.replica.history().any { it == edit })
    }
    @Test fun mutualExclusionDuringPartitionDoesNotInventReconciliation() = World().use { w ->
        w.a.replica.commit { _, _ -> listOf(Operation.Membership(w.b.replica.author, false)) }
        w.b.replica.commit { _, _ -> listOf(Operation.Membership(w.a.replica.author, false)) }
        listOf(w.a,w.b).forEach { DirectorySync(it, w.share).publish() }
        repeat(2) { listOf(w.a,w.b).forEach { DirectorySync(it, w.share).poll() } }
        assertFalse(w.a.replica.canExchange(w.b.replica.author))
        assertFalse(w.b.replica.canExchange(w.a.replica.author))
        assertNotEquals(w.a.replica.snapshot().frontier, w.b.replica.snapshot().frontier)
    }
    @Test fun unavailableShareDoesNotPreventLocalCommitsAndLaterPublication() = World().use { w ->
        Files.write(w.share, byteArrayOf(1)) // A file where a directory is required simulates unavailable access.
        val edit = w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        assertFails { DirectorySync(w.a, w.share).publish() }
        assertTrue(w.a.replica.history().any { it == edit })
        Files.delete(w.share)
        DirectorySync(w.a, w.share).publish()
        DirectorySync(w.b, w.share).poll()
        assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
    }
    @Test fun referencesRemainPendingUntilAllDirectoryChunksArriveAndCorruptionIsRetried() = World().use { w ->
        val bytes = ByteArray(BlobStore.CHUNK_SIZE + 59) { (it % 251).toByte() }
        val blob = w.a.blobs.put(bytes.inputStream())
        w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "blob", Shape.REGISTER, mapOf("value" to blob))) }
        DirectorySync(w.a, w.share).publish()
        val area = w.area(w.a)
        val last = area.resolve("chunk-${blob.chunks.last()}.payload")
        val complete = Files.readAllBytes(last)
        Files.write(last, byteArrayOf(1,2))
        val receiver = DirectorySync(w.b, w.share)
        repeat(2) { receiver.poll() }
        assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
        assertNull(w.b.blobs.open(blob))
        Files.write(last, complete)
        receiver.poll()
        assertContentEquals(bytes, w.b.blobs.open(blob)!!.use { it.readAllBytes() })
        // Local storage corruption is also detected before bytes reach a consumer.
        Files.write(w.root.resolve("b/chunks").resolve(blob.chunks.first()), byteArrayOf(7))
        assertFailsWith<IllegalArgumentException> { w.b.blobs.open(blob) }
        receiver.poll()
        assertContentEquals(bytes, w.b.blobs.open(blob)!!.use { it.readAllBytes() })
    }
    @Test fun replacingPublisherDirectoryDuringWriteCannotRedirectPublication() = World().use { w ->
        val outside = Files.createDirectory(w.root.resolve("outside-race"))
        val sentinel = outside.resolve("sentinel")
        Files.writeString(sentinel, "untouched")
        val area = w.area(w.a)
        val retired = area.resolveSibling("retired")
        var swapped = false
        DirectorySync(w.a, w.share) { boundary ->
            if (!swapped && boundary == PublicationBoundary.PAYLOAD_PARTIAL) {
                Files.move(area, retired)
                Files.createSymbolicLink(area, outside)
                swapped = true
            }
        }.publish()
        assertTrue(swapped)
        assertEquals(listOf("sentinel"), Files.list(outside).use { paths -> paths.map { it.fileName.toString() }.toList() })
        assertEquals("untouched", Files.readString(sentinel))
        assertTrue(Files.list(retired).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".ready") } })
        Files.delete(area); Files.move(retired, area)
        DirectorySync(w.a, w.share).publish()
        DirectorySync(w.b, w.share).poll()
        assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
    }

    @Test fun replacingOpenedDirectoryCannotRedirectReads() = World().use { w ->
        DirectorySync(w.a, w.share).publish()
        val area = w.area(w.a)
        Files.writeString(area.resolve("proof"), "original")
        val outside = Files.createDirectory(w.root.resolve("outside-read"))
        Files.writeString(outside.resolve("proof"), "redirected")
        SafeShare(w.share, create = false).use { mounted ->
            mounted.directory(mounted.root, area.parent.fileName.toString(), false).use { workspace ->
                mounted.directory(workspace, area.fileName.toString(), false).use { pinned ->
                    Files.move(area, area.resolveSibling("retired"))
                    Files.createSymbolicLink(area, outside)
                    assertEquals("original", pinned.read("proof", 100).decodeToString())
                }
            }
        }
    }

    @Test fun maliciousNamesAndSymlinkCandidatesNeverReadOrWriteOutsidePublisherArea() = World().use { w ->
        DirectorySync(w.a, w.share).publish()
        val area = w.area(w.a)
        val outside = w.root.resolve("outside")
        Files.createDirectory(outside)
        val sentinel = outside.resolve("sentinel")
        Files.writeString(sentinel, "unchanged")
        val forged = area.resolve("batch-${"0".repeat(64)}.ready")
        Files.createSymbolicLink(forged, sentinel)
        assertTrue(DirectorySync(w.b, w.share).poll().deferred > 0)
        assertEquals("unchanged", Files.readString(sentinel))
        val own = w.area(w.b)
        Files.createSymbolicLink(own, outside)
        assertFailsWith<java.io.IOException> { DirectorySync(w.b, w.share).publish() }
        assertEquals(listOf("sentinel"), Files.list(outside).use { paths -> paths.map { it.fileName.toString() }.toList() })
    }

    @Test fun publicationsInAnAreaNotOwnedByTheirSignerAreIgnored() = World().use { w ->
        val edit = w.c.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        DirectorySync(w.c, w.share).publish()
        // c's correctly signed publications now sit in the area that belongs to a.
        Files.move(w.area(w.c), w.area(w.a))
        val poll = DirectorySync(w.b, w.share).poll()
        assertTrue(poll.deferred > 0)
        assertFalse(w.b.replica.history().any { it.batch.id == edit.batch.id })
    }
    @Test fun payloadWhoseHashDoesNotMatchItsNameIsDeferred() = World().use { w ->
        val first = BatchCodec.encode(w.c.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) })
        val second = BatchCodec.encode(w.c.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) })
        // A same-size, validly signed substitute is caught only by the name/hash binding.
        assertEquals(first.size, second.size)
        DirectorySync(w.c, w.share).publish()
        val name = "batch-${BatchCodec.hash(first)}"
        Files.write(w.area(w.c).resolve("$name.payload"), second)
        val poll = DirectorySync(w.b, w.share).poll()
        assertTrue(poll.diagnostics.any { it.startsWith("$name.ready") }, poll.diagnostics.toString())
        assertFalse(w.b.replica.history().any { BatchCodec.encode(it).contentEquals(first) })
    }
    @Test fun pollReportsAMissingShareOrWorkspaceAreaAsDeferred() = World().use { w ->
        val unavailable = DirectoryPoll(0, 1, listOf("Share workspace area unavailable"))
        assertEquals(unavailable, DirectorySync(w.b, w.share).poll())
        Files.createDirectories(w.share)
        assertEquals(unavailable, DirectorySync(w.b, w.share).poll())
        DirectorySync(w.a, w.share).publish()
        assertEquals(0, DirectorySync(w.b, w.share).poll().deferred)
    }
    @Test fun deniedShareAccessKeepsLocalCommitsAndRecoversWhenRestored() = World().use { w ->
        DirectorySync(w.a, w.share).publish()
        Assume.assumeTrue(Files.getFileAttributeView(w.share, PosixFileAttributeView::class.java) != null)
        Assume.assumeFalse("root bypasses permissions", System.getProperty("user.name") == "root")
        val edit = w.a.replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT)) }
        val before = w.b.replica.snapshot()
        val permissions = Files.getPosixFilePermissions(w.share)
        Files.setPosixFilePermissions(w.share, emptySet())
        try {
            // Throwing or reporting deferred are both acceptable; applying or losing anything is not.
            runCatching { DirectorySync(w.a, w.share).publish() }
            runCatching { DirectorySync(w.b, w.share).poll() }.onSuccess { assertEquals(0, it.applied) }
            assertEquals(before, w.b.replica.snapshot())
        } finally { Files.setPosixFilePermissions(w.share, permissions) }
        assertTrue(w.a.replica.history().any { it == edit })
        DirectorySync(w.a, w.share).publish()
        DirectorySync(w.b, w.share).poll()
        assertEquals(w.a.replica.snapshot(), w.b.replica.snapshot())
    }
}
