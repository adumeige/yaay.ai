package ai.yaay.crdt

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

/** Byte-level contracts of the signed batch and transport envelope encodings, which parse untrusted input. */
class CodecTest {
    private val key = JvmBatchCrypto.generate()
    private val atoms = mapOf(
        "true" to Atom.Bool(true), "false" to Atom.Bool(false),
        "negative" to Atom.Number(-2.5), "negativeZero" to Atom.Number(-0.0), "max" to Atom.Number(Double.MAX_VALUE), "subnormal" to Atom.Number(Double.MIN_VALUE),
        "string" to Atom.Str("é🌍\nline"), "empty" to Atom.Str(""),
        "instance" to Atom.Instance("i:1:0"), "ref" to Atom.Ref("r:1:0"),
        "some" to Atom.Variant("Some", "p:1:0"), "none" to Atom.Variant("None", null),
        "blob" to Atom.Blob("a".repeat(64), 1_048_635, listOf("b".repeat(64), "c".repeat(64))),
    )
    private fun batch(operations: List<Operation>): SignedBatch =
        key.sign(Batch("codec", 1, BatchId(key.author, 3), Frontier(mapOf(key.author to 3L, "other" to 7L)), operations))
    private val everyKind: SignedBatch = batch(
        Shape.entries.map { Operation.Create("o:${it.ordinal}", "type-${it.name}", it) } +
            Operation.Create("record", "record-type", Shape.RECORD, atoms) +
            atoms.values.map { Operation.Assign("record", "field", it) } +
            listOf(
                Operation.Place("item", "list", "anchor"), Operation.Place("item", null, null),
                Operation.Delete("item"),
                Operation.EditText("text", null, "hé🌍", setOf("text/1", "text/0")), Operation.EditText("text", "text/0", "", emptySet()),
                Operation.Membership("peer", true), Operation.Membership("peer", false),
                Operation.Unknown(99, listOf(1, 2, 3)),
            )
    )

    @Test fun everyAtomAndOperationKindRoundTripsExactly() {
        val bytes = BatchCodec.encode(everyKind)
        val decoded = BatchCodec.decode(bytes)
        assertEquals(everyKind, decoded)
        assertTrue(key.verify(decoded))
        for ((name, atom) in atoms) {
            val single = batch(listOf(Operation.Assign("record", name, atom)))
            assertEquals(single, BatchCodec.decode(BatchCodec.encode(single)), name)
        }
    }

    @Test fun mutatedBatchBytesAreRejectedOrCanonicalAndNeverForgeASignature() {
        val original = BatchCodec.encode(everyKind)
        val random = Random(20260929)
        repeat(5_000) {
            val mutated = mutate(original, random)
            // Exceptions are the rejection path; Errors (overflow, exhaustion) fail the test.
            val decoded = try { BatchCodec.decode(mutated) } catch (_: Exception) { null } ?: return@repeat
            assertContentEquals(mutated, BatchCodec.encode(decoded), "accepted bytes must be canonical")
            assertTrue(mutated.contentEquals(original) || !key.verify(decoded), "mutation produced a validly signed batch")
        }
    }

    @Test fun mutatedTransportEnvelopesNeverVerify() {
        val root = Files.createTempDirectory("yaay-codec-")
        try {
            DurableReplica.open(root.resolve("r"), "codec", validator = { _, _, _, _ -> }).use { replica ->
                val original = Envelope(replica.author, replica.workspace, "request", "nonce", Wire.write { writeUTF("inventory"); writeUTF("") }).sign(replica)
                assertEquals("nonce", Envelope.verify(original).nonce)
                val random = Random(20260930)
                repeat(2_000) {
                    val mutated = mutate(original, random)
                    val accepted = try { Envelope.verify(mutated) } catch (_: Exception) { null }
                    if (accepted != null) assertContentEquals(original, mutated, "a mutated envelope verified")
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun mutate(original: ByteArray, random: Random): ByteArray {
        val bytes = original.copyOf()
        val at = random.nextInt(bytes.size)
        return when (random.nextInt(6)) {
            0 -> bytes.also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
            1 -> bytes.also { it[at] = random.nextInt(256).toByte() }
            2 -> bytes.copyOf(at)
            3 -> bytes.copyOfRange(0, at) + random.nextBytes(1 + random.nextInt(8)) + bytes.copyOfRange(at, bytes.size)
            4 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(minOf(bytes.size, at + 1 + random.nextInt(8)), bytes.size)
            // Corrupt a length or count field: a big-endian Int at a random offset.
            else -> bytes.also {
                val value = listOf(-1, 0, Int.MAX_VALUE, 100_001, 16 * 1024 * 1024 + 1).random(random)
                val start = minOf(at, it.size - 4).coerceAtLeast(0)
                for (i in 0 until minOf(4, it.size)) it[start + i] = (value ushr (24 - 8 * i)).toByte()
            }
        }
    }
}
