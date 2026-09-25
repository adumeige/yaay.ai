package ai.yaay.documents.types

import ai.yaay.crdt.*

/** Finite immutable key values. Changing a map key selects another entry, never edits the key. */
public sealed interface KeyValue {
    public data class Atomic(public val atom: Atom) : KeyValue
    public data class Record(public val fields: Map<String, KeyValue>) : KeyValue
    public data class Variant(public val tag: String, public val payload: KeyValue? = null) : KeyValue
    public data class Sequence(public val items: List<KeyValue>) : KeyValue
    public data class MapEntries(public val entries: Map<KeyValue, KeyValue>) : KeyValue
}

public object KeyEncoding {
    private fun token(value: String): String { unicodeScalars(value); return "${value.length}:$value" }
    public fun encode(value: KeyValue): String = when (value) {
        is KeyValue.Atomic -> when (val atom = value.atom) {
            is Atom.Bool -> if (atom.value) "b1" else "b0"
            is Atom.Number -> { require(atom.value.isFinite()); "n" + token((if (atom.value == 0.0) 0.0 else atom.value).toString()) }
            is Atom.Str -> "s" + token(atom.value)
            is Atom.Ref -> "r" + token(atom.id)
            is Atom.Blob -> "h" + token(atom.hash) + token(atom.size.toString()) + "${atom.chunks.size}:" + atom.chunks.joinToString("") { token(it) }
            else -> throw IllegalArgumentException("Keys cannot contain mutable instance selections")
        }
        is KeyValue.Record -> "p${value.fields.size}:" + value.fields.entries.sortedBy { it.key }.joinToString("") { token(it.key) + encode(it.value) }
        is KeyValue.Variant -> "u" + token(value.tag) + if (value.payload == null) "0" else "1" + encode(value.payload)
        is KeyValue.Sequence -> "l${value.items.size}:" + value.items.joinToString("") { encode(it) }
        is KeyValue.MapEntries -> {
            val entries = value.entries.map { encode(it.key) to encode(it.value) }.sortedBy { it.first }
            require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate canonical map key" }
            "m${entries.size}:" + entries.joinToString("") { it.first + it.second }
        }
    }
    private class Reader(val text: String) {
        var at = 0
        fun char(): Char { require(at < text.length); return text[at++] }
        fun count(): Int {
            val end = text.indexOf(':', at); require(end > at)
            val n = text.substring(at, end).toInt(); require(n in 0..100_000); at = end + 1; return n
        }
        fun token(): String { val n = count(); require(n <= text.length - at); return text.substring(at, at + n).also { at += n } }
        fun value(depth: Int = 0): KeyValue {
            require(depth < 128)
            return when (char()) {
                'b' -> KeyValue.Atomic(Atom.Bool(when (char()) { '0' -> false; '1' -> true; else -> throw IllegalArgumentException("Invalid boolean") }))
                'n' -> KeyValue.Atomic(Atom.Number(token().toDouble()))
                's' -> KeyValue.Atomic(Atom.Str(token()))
                'r' -> KeyValue.Atomic(Atom.Ref(token()))
                'h' -> KeyValue.Atomic(Atom.Blob(token(), token().toLong(), List(count()) { token() }))
                'p' -> { val size = count(); KeyValue.Record(buildMap { repeat(size) { val key = token(); require(put(key, value(depth + 1)) == null) } }) }
                'u' -> KeyValue.Variant(token(), when (char()) { '0' -> null; '1' -> value(depth + 1); else -> throw IllegalArgumentException("Invalid variant") })
                'l' -> KeyValue.Sequence(List(count()) { value(depth + 1) })
                'm' -> { val size = count(); KeyValue.MapEntries(buildMap { repeat(size) { val key = value(depth + 1); require(put(key, value(depth + 1)) == null) } }) }
                else -> throw IllegalArgumentException("Invalid key encoding")
            }
        }
    }
    public fun decode(text: String): KeyValue = Reader(text).run { value().also { require(at == text.length && encode(it) == text) } }
    public fun validate(types: TypeSystem, type: Type, value: KeyValue, depth: Int = 0) {
        require(depth < 128) { "Key nesting exceeds limit" }
        val shape = types.structure(type)
        fun recur(type: Type, value: KeyValue) { validate(types, type, value, depth + 1) }
        val atom = (value as? KeyValue.Atomic)?.atom
        when (shape) {
            Type.Scalar.BOOLEAN -> require(atom is Atom.Bool)
            Type.Scalar.NUMBER -> require(atom is Atom.Number && atom.value.isFinite())
            Type.Scalar.STRING, Type.Scalar.TEXT -> require(atom is Atom.Str)
            Type.Scalar.BLOB -> require(atom is Atom.Blob && atom.size >= 0 && atom.hash.matches(Regex("[0-9a-f]{64}")) && atom.chunks.all { it.matches(Regex("[0-9a-f]{64}")) })
            is Type.Enum -> require(atom is Atom.Str && atom.value in shape.choices)
            is Type.Ref -> require(atom is Atom.Ref && types.referenceMatches(atom.id, shape.target))
            is Type.Record -> {
                require(value is KeyValue.Record && value.fields.keys == shape.fields.keys)
                shape.fields.forEach { (field, type) -> recur(type, value.fields.getValue(field)) }
            }
            is Type.Sum -> {
                require(value is KeyValue.Variant && shape.variants.containsKey(value.tag))
                val payload = shape.variants[value.tag]
                if (payload == null) require(value.payload == null) else recur(payload, value.payload ?: throw IllegalArgumentException("Missing key payload"))
            }
            is Type.Sequence -> { require(value is KeyValue.Sequence); value.items.forEach { recur(shape.element, it) } }
            is Type.MapOf -> { require(value is KeyValue.MapEntries); value.entries.forEach { (key, entry) -> recur(shape.key, key); recur(shape.value, entry) } }
            else -> throw IllegalArgumentException("Unresolved map key type")
        }
    }
    public fun toField(types: TypeSystem, type: Type, value: KeyValue): String {
        validate(types, type, value)
        return if (types.canonical(type) == Type.Scalar.STRING) ((value as KeyValue.Atomic).atom as Atom.Str).value.also { unicodeScalars(it) } else encode(value)
    }
    public fun fromField(types: TypeSystem, type: Type, field: String): KeyValue {
        val value = if (types.canonical(type) == Type.Scalar.STRING) KeyValue.Atomic(Atom.Str(field)) else decode(field)
        require(toField(types, type, value) == field)
        return value
    }
}
