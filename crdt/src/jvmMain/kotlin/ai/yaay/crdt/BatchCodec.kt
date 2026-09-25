package ai.yaay.crdt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** V1 uses bounded, length-prefixed big-endian fields and sorted map/set entries. */
public object BatchCodec {
    public const val MAX_FRAME: Int = 16 * 1024 * 1024
    private const val MAX_ITEMS: Int = 100_000
    private fun encode(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use(write) }.toByteArray().also { require(it.size <= MAX_FRAME) }
    private fun DataOutputStream.bytes(value: ByteArray) { require(value.size <= MAX_FRAME); writeInt(value.size); write(value) }
    private fun DataOutputStream.string(value: String) { unicodeScalars(value); bytes(value.toByteArray(Charsets.UTF_8)) }
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) string(value) }
    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_ITEMS) }
    private fun DataInputStream.bytes(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_FRAME && size <= available()) { "Invalid field length" }
        return ByteArray(size).also { readFully(it) }
    }
    private fun DataInputStream.string(): String = bytes().let { bytes ->
        bytes.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8" } }
    }
    private fun DataInputStream.optional(): String? = if (readBoolean()) string() else null
    private fun DataOutputStream.atom(value: Atom) {
        when (value) {
            is Atom.Bool -> { writeByte(1); writeBoolean(value.value) }
            is Atom.Number -> { writeByte(2); writeDouble(value.value) }
            is Atom.Str -> { writeByte(3); string(value.value) }
            is Atom.Instance -> { writeByte(4); string(value.id) }
            is Atom.Ref -> { writeByte(5); string(value.id) }
            is Atom.Variant -> { writeByte(6); string(value.tag); optional(value.payload) }
            is Atom.Blob -> { writeByte(7); string(value.hash); writeLong(value.size); writeInt(value.chunks.size); value.chunks.forEach { string(it) } }
        }
    }
    private fun DataInputStream.atom(): Atom = when (readUnsignedByte()) {
        1 -> Atom.Bool(readBoolean())
        2 -> Atom.Number(readDouble())
        3 -> Atom.Str(string())
        4 -> Atom.Instance(string())
        5 -> Atom.Ref(string())
        6 -> Atom.Variant(string(), optional())
        7 -> Atom.Blob(string(), readLong(), List(count()) { string() })
        else -> throw ProtocolFault("Unknown atomic encoding")
    }
    private fun operation(op: Operation): Pair<Int, ByteArray> = when (op) {
        is Operation.Create -> 1 to encode { string(op.id); string(op.type); writeInt(op.shape.ordinal); writeInt(op.fields.size); op.fields.toSortedMap().forEach { (key, value) -> string(key); atom(value) } }
        is Operation.Assign -> 2 to encode { string(op.target); string(op.field); atom(op.value) }
        is Operation.Place -> 3 to encode { string(op.target); optional(op.parent); optional(op.after) }
        is Operation.Delete -> 4 to encode { string(op.target) }
        is Operation.EditText -> 5 to encode { string(op.target); optional(op.after); string(op.insert); writeInt(op.delete.size); op.delete.sorted().forEach { string(it) } }
        is Operation.Membership -> 6 to encode { string(op.peer); writeBoolean(op.admitted) }
        is Operation.Unknown -> { require(op.tag !in 1..6); op.tag to op.payload.toByteArray() }
    }
    private fun operation(tag: Int, bytes: ByteArray): Operation {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val op = with(input) {
            when (tag) {
                1 -> Operation.Create(string(), string(), Shape.entries[readInt()], buildMap { repeat(input.count()) { val key = input.string(); require(put(key, input.atom()) == null) } })
                2 -> Operation.Assign(string(), string(), atom())
                3 -> Operation.Place(string(), optional(), optional())
                4 -> Operation.Delete(string())
                5 -> Operation.EditText(string(), optional(), string(), List(count()) { string() }.toSet())
                6 -> Operation.Membership(string(), readBoolean())
                else -> { skipBytes(available()); Operation.Unknown(tag, bytes.toList()) }
            }
        }
        require(input.available() == 0)
        return op
    }
    public fun logical(batch: Batch): ByteArray = encode {
        string("yaay.batch")
        writeInt(batch.version)
        string(batch.workspace)
        string(batch.id.author)
        writeLong(batch.id.counter)
        writeInt(batch.vector.counters.size)
        batch.vector.counters.toSortedMap().forEach { (key, value) -> string(key); writeLong(value) }
        writeInt(batch.operations.size)
        batch.operations.forEach { op -> val (tag, body) = operation(op); writeInt(tag); bytes(body) }
    }
    public fun encode(batch: SignedBatch): ByteArray = encode { bytes(logical(batch.batch)); string(batch.digest); bytes(batch.signature.toByteArray()) }
    public fun decode(bytes: ByteArray): SignedBatch {
        require(bytes.size <= MAX_FRAME)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val body = input.bytes()
        val logical = DataInputStream(ByteArrayInputStream(body))
        val batch = with(logical) {
            require(string() == "yaay.batch")
            val version = readInt()
            val workspace = string()
            val id = BatchId(string(), readLong())
            val vector = buildMap { repeat(logical.count()) { val key = logical.string(); require(put(key, logical.readLong()) == null) } }
            Batch(workspace, version, id, Frontier(vector), List(count()) { operation(readInt(), bytes()) })
        }
        require(logical.available() == 0)
        val result = SignedBatch(batch, input.string(), input.bytes().toList())
        require(input.available() == 0 && encode(result).contentEquals(bytes)) { "Noncanonical batch encoding" }
        return result
    }
    public fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Keys use Ed25519, encoded as URL-safe unpadded X.509 public-key bytes. */
public class JvmBatchCrypto(private val keys: KeyPair) : BatchCrypto {
    public val author: String = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.public.encoded)
    public fun signBytes(bytes: ByteArray): ByteArray = Signature.getInstance("Ed25519").run { initSign(keys.private); update(bytes); sign() }
    override fun sign(batch: Batch): SignedBatch {
        require(batch.id.author == author)
        val bytes = BatchCodec.logical(batch)
        val signature = Signature.getInstance("Ed25519").run { initSign(keys.private); update(bytes); sign() }
        return SignedBatch(batch, BatchCodec.hash(bytes), signature.toList())
    }
    override fun verify(batch: SignedBatch): Boolean = try {
        val bytes = BatchCodec.logical(batch.batch)
        batch.digest == BatchCodec.hash(bytes) && verifyBytes(batch.batch.id.author, bytes, batch.signature.toByteArray())
    } catch (_: Exception) { false }
    public companion object {
        public fun verifyBytes(author: String, bytes: ByteArray, signature: ByteArray): Boolean = try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(author)))
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(key.encoded) == author) { "Noncanonical public key identity" }
            Signature.getInstance("Ed25519").run { initVerify(key); update(bytes); verify(signature) }
        } catch (_: Exception) { false }
        public fun generate(): JvmBatchCrypto = JvmBatchCrypto(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
    }
}
