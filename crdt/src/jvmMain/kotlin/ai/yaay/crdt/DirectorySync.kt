package ai.yaay.crdt

import java.nio.file.Path
import java.nio.file.NoSuchFileException

public enum class PublicationBoundary { PAYLOAD_PARTIAL, PAYLOAD_PUBLISHED, READY_PUBLISHED }

public data class DirectoryPoll(public val applied: Int, public val deferred: Int, public val diagnostics: List<String>)

/** Immutable payload plus signed ready indication in each publisher's own workspace area. */
public class DirectorySync(private val endpoint: SyncEndpoint, share: Path, private val publicationObserver: (PublicationBoundary) -> Unit = {}) {
    private val share: Path = share.toAbsolutePath().normalize()
    private val workspaceName: String = BatchCodec.hash(endpoint.replica.workspace.toByteArray())
    init { require(!endpoint.replica.privateRoot) }
    private fun publish(area: SafeDirectory, kind: String, hash: String, bytes: ByteArray) {
        val name = "$kind-$hash"
        val declaration = Wire.write { writeUTF(kind); writeUTF(hash); writeInt(bytes.size) }
        val ready = Envelope(endpoint.replica.author, endpoint.replica.workspace, "publication", name, declaration).sign(endpoint.replica)
        area.write("$name.payload", bytes) { publicationObserver(PublicationBoundary.PAYLOAD_PARTIAL) }
        publicationObserver(PublicationBoundary.PAYLOAD_PUBLISHED)
        area.write("$name.ready", ready)
        publicationObserver(PublicationBoundary.READY_PUBLISHED)
    }
    public fun publish() {
        SafeShare(share, create = true).use { mounted ->
            mounted.directory(mounted.root, workspaceName, create = true).use { workspace ->
                mounted.directory(workspace, BatchCodec.hash(endpoint.replica.author.toByteArray()), create = true).use { area ->
                    endpoint.batches().forEach { (hash, bytes) -> publish(area, "batch", hash, bytes) }
                    endpoint.referencedBlobs().flatMap { it.chunks }.distinct().forEach { hash ->
                        endpoint.blobs.availableChunk(hash)?.let { publish(area, "chunk", hash, it) }
                    }
                }
            }
        }
    }
    public fun poll(): DirectoryPoll {
        var applied = 0
        var deferred = 0
        val diagnostics = mutableListOf<String>()
        val joining = endpoint.replica.history().isEmpty()
        val bootstrap = linkedMapOf<String, MutableList<SignedBatch>>()
        val mounted = try { SafeShare(share, create = false) } catch (_: NoSuchFileException) {
            return DirectoryPoll(0, 1, listOf("Share workspace area unavailable"))
        }
        mounted.use {
            val workspace = try { mounted.directory(mounted.root, workspaceName, create = false) } catch (_: NoSuchFileException) {
                return DirectoryPoll(0, 1, listOf("Share workspace area unavailable"))
            }
            workspace.use {
                for (publisher in workspace.names()) {
                    if (!publisher.matches(Regex("[0-9a-f]{64}"))) continue
                    val area = try { mounted.directory(workspace, publisher, create = false) } catch (failure: java.io.IOException) {
                        deferred++
                        if (diagnostics.size < 100) diagnostics.add("$publisher: ${failure.message}")
                        continue
                    }
                    area.use {
                        for (candidate in area.names().filter { it.endsWith(".ready") }) {
                            try {
                                val filename = candidate
                                require(filename.matches(Regex("(batch|chunk)-[0-9a-f]{64}\\.ready")))
                                val ready = Envelope.verify(area.read(candidate, 8192))
                                require(ready.purpose == "publication" && ready.workspace == endpoint.replica.workspace)
                                require(BatchCodec.hash(ready.author.toByteArray()) == publisher)
                                require(filename == "${ready.nonce}.ready")
                                if (!joining && !endpoint.replica.canExchange(ready.author)) { deferred++; continue }
                                Wire.read(ready.payload) {
                                    val kind = readUTF()
                                    val hash = readUTF()
                                    val size = readInt()
                                    require(kind in setOf("batch", "chunk") && hash.matches(Regex("[0-9a-f]{64}")))
                                    require(ready.nonce == "$kind-$hash")
                                    val max = if (kind == "batch") BatchCodec.MAX_FRAME else BlobStore.CHUNK_SIZE
                                    require(size in 0..max)
                                    val payload = area.read("$kind-$hash.payload", max)
                                    require(payload.size == size && BatchCodec.hash(payload) == hash)
                                if (kind == "batch") {
                            
        if (joining) bootstrap.getOrPut(ready.author) { mutableListOf() }.add(BatchCodec.decode(payload))
                                    else if (endpoint.receiveBatch(payload, ready.author) == IngestResult.APPLIED) applied++
                                } else {
                                    if (endpoint.referencedBlobs().any { hash in it.chunks }) endpoint.blobs.putChunk(hash, payload) else deferred++
                                }
                            }
                        } catch (failure: Exception) {
                            // Partially visible payload/ready files are retried on every poll, never applied piecemeal.
                            deferred++
                            if (diagnostics.size < 100) diagnostics.add("$candidate: ${failure.message ?: failure.javaClass.simpleName}")
                        }
                    }
                }
            }
        }
        }
        if (joining) {
            for ((publisher, batches) in bootstrap) {
                try {
                    endpoint.replica.bootstrap(batches, publisher)
                    applied += batches.size
                    break
                } catch (failure: IllegalArgumentException) {
                    deferred++
                    if (diagnostics.size < 100) diagnostics.add("Bootstrap pending: ${failure.message}")
                }
            }
        }
        return DirectoryPoll(applied, deferred, diagnostics)
    }
}
