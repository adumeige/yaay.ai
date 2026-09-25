package ai.yaay.crdt

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributeView
import java.util.UUID

/** The configured share root is trusted; every descendant operation uses pinned directory handles. */
internal class SafeShare(private val path: Path, create: Boolean) : AutoCloseable {
    val root: SafeDirectory
    init {
        if (create) Files.createDirectories(path)
        root = SafeDirectory(secure(Files.newDirectoryStream(path)))
    }
    fun directory(parent: SafeDirectory, name: String, create: Boolean): SafeDirectory {
        validName(name)
        try { return SafeDirectory(parent.stream.newDirectoryStream(Path.of(name), NOFOLLOW_LINKS)) }
        catch (missing: NoSuchFileException) { if (!create) throw missing }
        // SecureDirectoryStream has no mkdir operation. Create a random empty directory
        // directly under the trusted root, then move it relative to pinned handles.
        val temporary = Files.createTempDirectory(path, ".directory-").fileName
        try {
            try { root.stream.move(temporary, parent.stream, Path.of(name)) }
            catch (_: FileAlreadyExistsException) { }
            catch (_: DirectoryNotEmptyException) { }
            return SafeDirectory(parent.stream.newDirectoryStream(Path.of(name), NOFOLLOW_LINKS))
        } finally { try { root.stream.deleteDirectory(temporary) } catch (_: NoSuchFileException) { } }
    }
    override fun close() { root.close() }
    companion object {
        private fun secure(stream: DirectoryStream<Path>): SecureDirectoryStream<Path> {
            if (stream is SecureDirectoryStream<Path>) return stream
            stream.close()
            throw UnsupportedOperationException("Mounted-directory sync requires a filesystem provider with secure directory handles")
        }
        fun validName(name: String) { require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) }
    }
}

internal class SafeDirectory(val stream: SecureDirectoryStream<Path>) : AutoCloseable {
    fun names(): List<String> = stream.map { it.fileName.toString() }
    fun read(name: String, max: Int): ByteArray {
        SafeShare.validName(name)
        val relative = Path.of(name)
        require(stream.getFileAttributeView(relative, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes().isRegularFile)
        return stream.newByteChannel(relative, setOf(READ, NOFOLLOW_LINKS)).use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                buffer.clear()
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= max - result.size()) { "Oversized publication" }
                result.write(buffer.array(), 0, count)
            }
            result.toByteArray()
        }
    }
    fun write(name: String, bytes: ByteArray, halfway: (() -> Unit)? = null) {
        SafeShare.validName(name)
        val temporary = Path.of(".publication-${UUID.randomUUID()}.tmp")
        try {
            stream.newByteChannel(temporary, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { output ->
                val buffer = ByteBuffer.wrap(bytes)
                if (halfway != null) {
                    buffer.limit(bytes.size / 2)
                    while (buffer.hasRemaining()) output.write(buffer)
                    halfway()
                    buffer.limit(bytes.size)
                }
                while (buffer.hasRemaining()) output.write(buffer)
                (output as? FileChannel)?.force(true)
            }
            // Removing an existing candidate through the handle never follows a symlink.
            // A gap in readiness is harmless: receivers retry their verified immutable candidates.
            try { stream.deleteFile(Path.of(name)) } catch (_: NoSuchFileException) { }
            stream.move(temporary, stream, Path.of(name))
        } finally { try { stream.deleteFile(temporary) } catch (_: NoSuchFileException) { } }
    }
    override fun close() { stream.close() }
}
