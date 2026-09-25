package ai.yaay.crdt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.*
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

public enum class IdentityBoundary { AFTER_TEMP_FORCE, AFTER_RENAME, AFTER_DIRECTORY_FORCE }

public enum class JournalBoundary { BEFORE_APPEND, AFTER_HEADER, AFTER_PAYLOAD, AFTER_CHECKSUM, AFTER_FORCE, BOOTSTRAP_INSTALLED }

/** Append-only length / canonical signed batch / SHA-256 frames. Only an incomplete tail is repaired. */
public class FileBatchJournal(private val path: Path, private val observe: (JournalBoundary) -> Unit = {}) : BatchJournal, AutoCloseable {
    private var channel: FileChannel = FileChannel.open(path, CREATE, READ, WRITE)
    private var failed: Boolean = false
    override fun read(): List<SignedBatch> {
        check(!failed)
        channel.position(0)
        val batches = mutableListOf<SignedBatch>()
        while (channel.position() < channel.size()) {
            val start = channel.position()
            val header = ByteBuffer.allocate(4)
            if (!readFully(header)) { repair(start); break }
            header.flip()
            val length = header.int
            require(length in 1..BatchCodec.MAX_FRAME) { "Corrupt journal frame at $start" }
            val body = ByteBuffer.allocate(length)
            val hash = ByteBuffer.allocate(32)
            if (!readFully(body) || !readFully(hash)) { repair(start); break }
            require(MessageDigest.getInstance("SHA-256").digest(body.array()).contentEquals(hash.array())) { "Journal integrity failure at $start" }
            batches.add(BatchCodec.decode(body.array()))
        }
        return batches
    }
    private fun readFully(buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return false
        return true
    }
    private fun repair(offset: Long) { channel.truncate(offset); channel.force(true) }
    override fun append(batch: SignedBatch) {
        check(!failed) { "Journal write failed; reopen for recovery" }
        val bytes = BatchCodec.encode(batch)
        try {
            observe(JournalBoundary.BEFORE_APPEND)
            channel.position(channel.size())
            fun write(buffer: ByteBuffer) { while (buffer.hasRemaining()) channel.write(buffer) }
            write(ByteBuffer.allocate(4).putInt(bytes.size).flip())
            observe(JournalBoundary.AFTER_HEADER)
            write(ByteBuffer.wrap(bytes))
            observe(JournalBoundary.AFTER_PAYLOAD)
            write(ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)))
            observe(JournalBoundary.AFTER_CHECKSUM)
            channel.force(true)
            observe(JournalBoundary.AFTER_FORCE)
        } catch (failure: Exception) {
            failed = true
            throw failure
        }
    }
    override fun initialize(batches: List<SignedBatch>) {
        check(!failed && channel.size() == 0L) { "Bootstrap requires an empty journal" }
        val temporary = Files.createTempFile(path.parent, ".bootstrap-", ".tmp")
        try {
            FileBatchJournal(temporary, observe).use { staged -> batches.forEach(staged::append) }
            channel.close()
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            DurableReplica.forceDirectory(path.parent)
            observe(JournalBoundary.BOOTSTRAP_INSTALLED)
            channel = FileChannel.open(path, READ, WRITE)
        } catch (failure: Exception) {
            failed = true
            throw failure
        } finally { Files.deleteIfExists(temporary) }
    }
    override fun close() { channel.close() }
}

/** One synchronized writer for commits and ingestion, protected by an OS process lock. */
public class DurableReplica private constructor(
    public val directory: Path,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val journal: FileBatchJournal,
    private val engine: ReplicaEngine,
    private val crypto: JvmBatchCrypto,
) : AutoCloseable {
    private var closed: Boolean = false
    private data class Revision(val value: Long, val closed: Boolean = false)
    private val revision = MutableStateFlow(Revision(0))
    private fun changed() { revision.value = Revision(revision.value.value + 1, closed) }

    /** Complete snapshots, conflated for slow consumers; ends when this replica closes. */
    public fun states(): Flow<ReplicaState> = revision.transformWhile { signal ->
        val state = synchronized(this) {
            if (closed) null else ReplicaState(snapshot(), engine.readOnly)
        }
        if (state != null) emit(state)
        !signal.closed && state != null
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /**
     * Lossless accepted-batch stream backed by retained history, not a bounded event bus.
     * Each collector replays from its own causal cursor, then follows new durable commits.
     * Slow collectors never block the writer. Cancellation releases the collector only.
     * Unsupported batches are retained by the journal but never emitted as applied commits.
     */
    public fun commits(after: Frontier = Frontier()): Flow<CommittedBatch> {
        val start = Frontier(after.counters.toMap())
        return flow {
            var cursor = start
            revision.transformWhile { signal -> emit(signal); !signal.closed }.collect {
                val history = synchronized(this@DurableReplica) {
                    engine.history.filter { it.batch.version == 1 && it.batch.operations.none { op -> op is Operation.Unknown } }
                }
                val prefix = mutableListOf<Batch>()
                for (signed in history) {
                    prefix.add(signed.batch)
                    if (!cursor.contains(signed.batch.id)) {
                        emit(CommittedBatch(copy(signed), Resolver.resolve(prefix).detached()))
                        cursor = Frontier(cursor.counters + (signed.batch.id.author to signed.batch.id.counter))
                    }
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    public val author: String get() = engine.author
    public val workspace: String get() = engine.workspace
    public val founder: String get() = engine.founder
    public val privateRoot: Boolean get() = engine.privateRoot
    @Synchronized internal fun signTransport(bytes: ByteArray): ByteArray { ensureOpen(); check(!privateRoot); return crypto.signBytes(bytes) }
    @Synchronized public fun snapshot(): Snapshot { ensureOpen(); return Resolver.resolve(engine.history.filter { it.batch.version == 1 && it.batch.operations.none { op -> op is Operation.Unknown } }.map { copy(it).batch }) }
    @Synchronized public fun history(): List<SignedBatch> { ensureOpen(); return engine.history.map(::copy) }
    @Synchronized public fun readOnly(): Boolean { ensureOpen(); return engine.readOnly }
    @Synchronized public fun canExchange(peer: String): Boolean { ensureOpen(); return engine.canExchange(peer) }
    /** Building operations under the writer lock allocates IDs without races between callers. */
    @Synchronized public fun commit(build: (id: (Int) -> String, snapshot: Snapshot) -> List<Operation>): SignedBatch {
        ensureOpen()
        val operations = build(engine::nextId, snapshot())
        // Detach caller-owned mutable lists/maps through the canonical representation before retaining them.
        val counter = engine.snapshot.frontier[author] + 1
        val detached = copy(SignedBatch(Batch(workspace, 1, BatchId(author, counter), Frontier(mapOf(author to counter)), operations), "", emptyList())).batch.operations
        return try { copy(engine.commit(detached)) } finally { changed() }
    }
    @Synchronized public fun bootstrap(batches: List<SignedBatch>, publisher: String) { ensureOpen(); try { engine.bootstrap(batches.map(::copy), publisher) } finally { changed() } }
    @Synchronized public fun ingest(batch: SignedBatch, publisher: String): IngestResult { ensureOpen(); return try { engine.ingest(copy(batch), publisher) } finally { changed() } }
    private fun ensureOpen() { check(!closed) { "Replica closed" } }
    private fun copy(batch: SignedBatch): SignedBatch = BatchCodec.decode(BatchCodec.encode(batch))
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            changed()
            try { journal.close() } finally { try { lock.release() } finally { lockChannel.close() } }
        }
    }
    public companion object {
        /** A null founder bootstraps a new workspace; joiners pin the existing workspace founder key. */
        public fun open(directory: Path, workspace: String, founder: String? = null, privateRoot: Boolean = false, validator: MutationValidator, journalObserver: (JournalBoundary) -> Unit = {}, identityObserver: (IdentityBoundary, String) -> Unit = { _, _ -> }): DurableReplica {
            Files.createDirectories(directory)
            val lockChannel = FileChannel.open(directory.resolve("writer.lock"), CREATE, WRITE)
            var lock: FileLock? = null
            var journal: FileBatchJournal? = null
            try {
                lock = lockChannel.tryLock() ?: error("Replica already has a live writer")
                val identity = directory.resolve("identity")
                if (!Files.exists(identity)) {
                    require(!Files.exists(directory.resolve("journal")) || Files.size(directory.resolve("journal")) == 0L) { "Replica identity is missing; restore its key backup instead of generating a new identity" }
                    val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                    val crypto = JvmBatchCrypto(keys)
                    val bytes = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
                        out.writeUTF("yaay.replica.1"); out.writeUTF(workspace); out.writeUTF(founder ?: crypto.author); out.writeBoolean(privateRoot)
                        out.writeInt(keys.public.encoded.size); out.write(keys.public.encoded)
                        out.writeInt(keys.private.encoded.size); out.write(keys.private.encoded)
                    } }.toByteArray()
                    val temp = if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
                        Files.createTempFile(directory, ".identity-", ".tmp", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
                    else Files.createTempFile(directory, ".identity-", ".tmp")
                    FileChannel.open(temp, CREATE, TRUNCATE_EXISTING, WRITE).use { out ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) out.write(buffer)
                        out.force(true)
                    }
                    identityObserver(IdentityBoundary.AFTER_TEMP_FORCE, crypto.author)
                    Files.move(temp, identity, StandardCopyOption.ATOMIC_MOVE)
                    identityObserver(IdentityBoundary.AFTER_RENAME, crypto.author)
                    forceDirectory(directory)
                    identityObserver(IdentityBoundary.AFTER_DIRECTORY_FORCE, crypto.author)
                }
                val input = DataInputStream(ByteArrayInputStream(Files.readAllBytes(identity)))
                require(input.readUTF() == "yaay.replica.1")
                require(input.readUTF() == workspace) { "Workspace identity mismatch" }
                val storedFounder = input.readUTF()
                require(founder == null || founder == storedFounder) { "Founder mismatch" }
                require(input.readBoolean() == privateRoot) { "Private root policy cannot change" }
                fun key(): ByteArray { val size = input.readInt(); require(size in 1..4096); return ByteArray(size).also { input.readFully(it) } }
                val factory = KeyFactory.getInstance("Ed25519")
                val publicKey = factory.generatePublic(X509EncodedKeySpec(key()))
                val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(key()))
                require(input.available() == 0)
                val crypto = JvmBatchCrypto(KeyPair(publicKey, privateKey))
                val challenge = "yaay.identity-check".toByteArray()
                require(JvmBatchCrypto.verifyBytes(crypto.author, challenge, crypto.signBytes(challenge))) { "Replica keypair does not match" }
                journal = FileBatchJournal(directory.resolve("journal"), journalObserver)
                forceDirectory(directory)
                val engine = ReplicaEngine(workspace, crypto.author, storedFounder, crypto, journal, validator, privateRoot)
                return DurableReplica(directory, lockChannel, lock, journal, engine, crypto)
            } catch (failure: Exception) {
                journal?.close(); lock?.release(); lockChannel.close()
                throw failure
            }
        }
        internal fun forceDirectory(directory: Path) { FileChannel.open(directory, READ).use { it.force(true) } }
    }
}
