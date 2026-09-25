package ai.yaay.graph

import ai.yaay.crdt.*
import java.io.*
import java.util.Base64

/** Versioned adapter-local encoding. This cache format is not a replication format. */
internal object ProjectionCodec {
    private fun encode(block: DataOutputStream.() -> Unit): String = Base64.getEncoder().encodeToString(
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { it.writeInt(1); it.block() } }.toByteArray())
    private fun <T> decode(value: String, block: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use {
            require(it.readInt() == 1) { "Unsupported projection cache version; rebuild required" }
            val result = it.block(); require(it.available() == 0); result
        }
    private fun DataOutputStream.string(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.size(): Int = readInt().also { require(it in 0..available()) { "Invalid projection cache length" } }
    private fun DataInputStream.string(): String = ByteArray(size()).also(::readFully).toString(Charsets.UTF_8)
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) string(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) string() else null
    private fun DataOutputStream.strings(values: List<String>) { writeInt(values.size); values.forEach { string(it) } }
    private fun DataInputStream.strings(): List<String> = List(size()) { string() }
    fun frontier(value: Frontier): String = encode {
        writeInt(value.counters.size); value.counters.toSortedMap().forEach { (author, count) -> string(author); writeLong(count) }
    }
    fun frontier(value: String): Frontier = decode(value) { Frontier(buildMap { repeat(size()) { put(string(), readLong()) } }) }
    fun objectValue(value: ResolvedObject): String = encode {
        string(value.id); string(value.type); string(value.shape.name); writeBoolean(value.deleted)
        optional(value.parent); optional(value.embeddedOwner); strings(value.children); string(value.text); strings(value.textPositions)
        writeInt(value.fields.size)
        value.fields.toSortedMap().forEach { (key, atom) ->
            string(key)
            when (atom) {
                is Atom.Bool -> { writeByte(0); writeBoolean(atom.value) }
                is Atom.Number -> { writeByte(1); writeDouble(atom.value) }
                is Atom.Str -> { writeByte(2); string(atom.value) }
                is Atom.Instance -> { writeByte(3); string(atom.id) }
                is Atom.Ref -> { writeByte(4); string(atom.id) }
                is Atom.Variant -> { writeByte(5); string(atom.tag); optional(atom.payload) }
                is Atom.Blob -> { writeByte(6); string(atom.hash); writeLong(atom.size); strings(atom.chunks) }
            }
        }
    }
    fun objectValue(value: String): ResolvedObject = decode(value) {
        val id = string(); val type = string(); val shape = Shape.valueOf(string()); val deleted = readBoolean()
        val parent = optional(); val owner = optional(); val children = strings(); val text = string(); val positions = strings()
        val fields = buildMap {
            repeat(size()) {
                val key = string()
                val atom = when (readUnsignedByte()) {
                    0 -> Atom.Bool(readBoolean()); 1 -> Atom.Number(readDouble()); 2 -> Atom.Str(string())
                    3 -> Atom.Instance(string()); 4 -> Atom.Ref(string()); 5 -> Atom.Variant(string(), optional())
                    6 -> Atom.Blob(string(), readLong(), strings()); else -> error("Invalid projected atom")
                }
                put(key, atom)
            }
        }
        ResolvedObject(id, type, shape, fields, deleted, parent, children, text, positions, owner)
    }
}
