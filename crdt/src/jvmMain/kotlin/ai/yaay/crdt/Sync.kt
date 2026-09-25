package ai.yaay.crdt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

internal object Wire {
    const val LIMIT = BatchCodec.MAX_FRAME + 8192
    fun write(body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(body) }.toByteArray().also { require(it.size <= LIMIT) }
    fun DataOutputStream.bytes(bytes: ByteArray) { require(bytes.size <= LIMIT); writeInt(bytes.size); write(bytes) }
    fun DataInputStream.bytes(): ByteArray { val n = readInt(); require(n in 0..LIMIT && n <= available()); return ByteArray(n).also { readFully(it) } }
    fun <T> read(bytes: ByteArray, body: DataInputStream.() -> T): T {
        require(bytes.size <= LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.body().also { require(input.available() == 0) } }
    }
}

internal data class Envelope(val author: String, val workspace: String, val purpose: String, val nonce: String, val payload: ByteArray) {
    fun logical(): ByteArray = Wire.write { writeUTF("yaay.sync.1"); writeUTF(author); writeUTF(workspace); writeUTF(purpose); writeUTF(nonce); with(Wire) { bytes(payload) } }
    fun sign(replica: DurableReplica): ByteArray {
        require(replica.author == author && replica.workspace == workspace)
        val body = logical()
        return Wire.write { with(Wire) { bytes(body); bytes(replica.signTransport(body)) } }
    }
    companion object {
        fun verify(bytes: ByteArray): Envelope = Wire.read(bytes) {
            val body = with(Wire) { bytes() }
            val signature = with(Wire) { bytes() }
            val envelope = Wire.read(body) {
                require(readUTF() == "yaay.sync.1")
                Envelope(readUTF(), readUTF(), readUTF(), readUTF(), with(Wire) { bytes() })
            }
            require(envelope.logical().contentEquals(body) && JvmBatchCrypto.verifyBytes(envelope.author, body, signature)) { "Invalid transport signature" }
            envelope
        }
    }
}

public fun interface ExchangeConnection { public fun request(bytes: ByteArray): ByteArray }

/** Shared admission/verification path. HTTP and directory delivery call this same receiver. */
public class SyncEndpoint(public val replica: DurableReplica, public val blobs: BlobStore, private val additionalContent: (Snapshot) -> List<Atom.Blob> = { emptyList() }) {
    public fun receiveBatch(bytes: ByteArray, publisher: String): IngestResult = replica.ingest(BatchCodec.decode(bytes), publisher)
    public fun referencedBlobs(): List<Atom.Blob> = (replica.history().flatMap { it.batch.operations }.flatMap { op ->
        when (op) { is Operation.Create -> op.fields.values.toList(); is Operation.Assign -> listOf(op.value); else -> emptyList() }
    }.filterIsInstance<Atom.Blob>() + additionalContent(replica.snapshot())).distinct()
    internal fun batches(): Map<String, ByteArray> = replica.history().map(BatchCodec::encode).associateBy { BatchCodec.hash(it) }
    public fun respond(bytes: ByteArray): ByteArray {
        val request = Envelope.verify(bytes)
        require(request.workspace == replica.workspace && request.purpose == "request")
        if (!replica.canExchange(request.author)) throw ProtocolFault("Excluded direct peer")
        val response = Wire.read(request.payload) {
            when (val command = readUTF()) {
                "inventory" -> {
                    val after = readUTF()
                    val hashes = batches().keys.sorted().filter { it > after }.take(128)
                    Wire.write { writeInt(hashes.size); hashes.forEach { writeUTF(it) } }
                }
                "batch-get" -> {
                    val bytes = batches()[readUTF()] ?: throw ProtocolFault("Unknown batch")
                    Wire.write { with(Wire) { bytes(bytes) } }
                }
                "batch-put" -> { receiveBatch(with(Wire) { bytes() }, request.author); byteArrayOf() }
                "chunk-get" -> {
                    val hash = readUTF()
                    val chunk = if (referencedBlobs().any { hash in it.chunks }) blobs.availableChunk(hash) else null
                    Wire.write { writeBoolean(chunk != null); if (chunk != null) with(Wire) { bytes(chunk) } }
                }
                "chunk-put" -> {
                    val hash = readUTF()
                    val payload = with(Wire) { bytes() }
                    val referenced = referencedBlobs().any { hash in it.chunks }
                    if (referenced) blobs.putChunk(hash, payload)
                    Wire.write { writeBoolean(referenced) }
                }
                else -> throw ProtocolFault("Unknown exchange command $command")
            }
        }
        return Envelope(replica.author, replica.workspace, "response", request.nonce, response).sign(replica)
    }
}

/** A client-initiated session uploads and downloads; retries reconcile immutable inventory. */
public class SyncClient(private val local: SyncEndpoint, private val peer: String, private val connection: ExchangeConnection) {
    private fun call(write: DataOutputStream.() -> Unit): ByteArray {
        require(!local.replica.privateRoot && (local.replica.canExchange(peer) || local.replica.history().isEmpty())) { "Excluded direct peer" }
        val nonce = UUID.randomUUID().toString()
        val request = Envelope(local.replica.author, local.replica.workspace, "request", nonce, Wire.write(write)).sign(local.replica)
        val response = Envelope.verify(connection.request(request))
        require(response.author == peer && response.workspace == local.replica.workspace && response.purpose == "response" && response.nonce == nonce) { "Response does not match pinned peer/session" }
        return response.payload
    }
    public fun exchange() {
        val remote = mutableSetOf<String>()
        var cursor = ""
        while (true) {
            val page = Wire.read(call { writeUTF("inventory"); writeUTF(cursor) }) {
                val count = readInt(); require(count in 0..128)
                List(count) { readUTF().also { require(it.matches(Regex("[0-9a-f]{64}"))) } }
            }
            require(page == page.sorted().distinct() && page.all { it > cursor })
            if (page.isEmpty()) break
            remote.addAll(page); cursor = page.last()
        }
        val ours = local.batches()
        // Download first so a fresh joiner can obtain its admission before authoring/uploading.
        val bootstrap = ours.isEmpty()
        val initial = mutableListOf<SignedBatch>()
        remote.filter { it !in ours }.forEach { hash ->
            val bytes = Wire.read(call { writeUTF("batch-get"); writeUTF(hash) }) { with(Wire) { bytes() } }
            require(BatchCodec.hash(bytes) == hash)
            if (bootstrap) initial.add(BatchCodec.decode(bytes)) else local.receiveBatch(bytes, peer)
        }
        if (bootstrap) local.replica.bootstrap(initial, peer)
        ours.filterKeys { it !in remote }.values.forEach { bytes -> call { writeUTF("batch-put"); with(Wire) { bytes(bytes) } } }
        for (blob in local.referencedBlobs()) {
            for (hash in blob.chunks.distinct()) {
                val chunk = local.blobs.availableChunk(hash)
                if (chunk == null) {
                    val fetched = Wire.read(call { writeUTF("chunk-get"); writeUTF(hash) }) { if (readBoolean()) with(Wire) { bytes() } else null }
                    if (fetched != null) local.blobs.putChunk(hash, fetched)
                } else Wire.read(call { writeUTF("chunk-put"); writeUTF(hash); with(Wire) { bytes(chunk) } }) { readBoolean() }
            }
        }
    }
}
