package ai.yaay.crdt

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.time.Duration

/** Immutable chunks live outside the journal. Consumers receive only fully verified content. */
public class BlobStore(private val directory: Path) {
    private val changed: Object = Object()
    init { Files.createDirectories(directory) }
    private fun path(hash: String): Path { require(hash.matches(Regex("[0-9a-f]{64}"))); return directory.resolve(hash) }
    public fun chunk(hash: String): ByteArray? {
        val file = path(hash)
        if (!Files.exists(file)) return null
        require(Files.size(file) <= CHUNK_SIZE) { "Oversized chunk" }
        return Files.readAllBytes(file).also { require(BatchCodec.hash(it) == hash) { "Corrupt chunk" } }
    }
    /** Sync treats a corrupt local chunk as missing so an intact replica can repair it. */
    internal fun availableChunk(hash: String): ByteArray? = try { chunk(hash) } catch (_: IllegalArgumentException) { null }
    public fun putChunk(hash: String, bytes: ByteArray) {
        require(bytes.size <= CHUNK_SIZE && BatchCodec.hash(bytes) == hash) { "Chunk hash mismatch" }
        val target = path(hash)
        val temporary = Files.createTempFile(directory, ".chunk-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { out ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) out.write(buffer)
                out.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            DurableReplica.forceDirectory(directory)
            synchronized(changed) { changed.notifyAll() }
        } finally { Files.deleteIfExists(temporary) }
    }
    public fun put(input: InputStream): Atom.Blob {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashes = mutableListOf<String>()
        var size = 0L
        while (true) {
            val bytes = input.readNBytes(CHUNK_SIZE)
            if (bytes.isEmpty()) break
            digest.update(bytes)
            size = Math.addExact(size, bytes.size.toLong())
            val hash = BatchCodec.hash(bytes)
            putChunk(hash, bytes)
            hashes.add(hash)
        }
        return Atom.Blob(digest.digest().joinToString("") { "%02x".format(it) }, size, hashes)
    }
    public fun missing(blob: Atom.Blob): List<String> = blob.chunks.distinct().filter { chunk(it) == null }
    /** Builds a verified temporary stream; no partial or mismatched bytes escape to consumers. */
    public fun open(blob: Atom.Blob): InputStream? {
        require(blob.size >= 0)
        path(blob.hash)
        val temporary = Files.createTempFile(directory, ".verified-", ".tmp")
        var handedOff = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newOutputStream(temporary).use { output ->
                for (hash in blob.chunks) {
                    val bytes = chunk(hash) ?: return null
                    size = Math.addExact(size, bytes.size.toLong())
                    require(size <= blob.size) { "Blob size mismatch" }
                    digest.update(bytes); output.write(bytes)
                }
            }
            require(size == blob.size && digest.digest().joinToString("") { "%02x".format(it) } == blob.hash) { "Blob integrity failure" }
            // DELETE_ON_CLOSE also cleans up when the stream is closed on Windows.
            return Files.newInputStream(temporary, READ, DELETE_ON_CLOSE).also { handedOff = true }
        } finally { if (!handedOff) Files.deleteIfExists(temporary) }
    }
    public fun await(blob: Atom.Blob, timeout: Duration): InputStream {
        val deadline = System.nanoTime() + timeout.toNanos()
        synchronized(changed) {
            while (true) {
                open(blob)?.let { return it }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw java.util.concurrent.TimeoutException("Blob is still pending")
                changed.wait(maxOf(1L, minOf(remaining / 1_000_000, 1000L)))
            }
        }
    }
    public companion object { public const val CHUNK_SIZE: Int = 1024 * 1024 }
}
