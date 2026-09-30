package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import ai.yaay.documents.types.SyntaxReader.Handle
import ai.yaay.documents.types.SyntaxReader.Ident
import ai.yaay.documents.types.SyntaxReader.Num
import ai.yaay.documents.types.SyntaxReader.Str

/**
 * Readable values, parsed against the type they must satisfy:
 *
 * ```
 * { name: "Ada", bio: "Mathematician", tags: ["math", "poetry"], status: Suspended { reason: "leave" } }
 * ```
 *
 * `true`/`false`, numbers and `"strings"` (also for `Text`); enum choices bare or quoted; `[...]` lists;
 * `{ field: value }` records and maps, where a structured map key is written in the key type's syntax;
 * `Tag`, `Tag(value)` or `Tag { ... }` variants; `ref(@handle)` references; `file("path")` to store a
 * new blob, or `blob("hash", size, ["chunk", ...])` for existing content. An `Any` slot takes its type
 * first: `Person { name: "Ada" }`, `Text "notes"`, `List<String> ["a"]`.
 *
 * A path addresses a nested object from a handle: `k3Fz9Q:4:0.tags.2` steps through record fields,
 * the active variant's tag, string map keys and list indices.
 */
public class ValueSyntax(private val snapshot: Snapshot, private val loadBlob: (String) -> Atom.Blob = { throw IllegalArgumentException("Blob files are not available here") }) {
    private val types = TypeSystem(snapshot)
    public val catalog: TypeCatalog = TypeCatalog(snapshot)

    public fun parse(text: String, type: Type): Value = SyntaxReader(text).run { value(type, 0).also { expectEnd() } }
    public fun parseKey(text: String, type: Type): KeyValue = SyntaxReader(text).run { key(type, 0).also { expectEnd() } }
    public fun label(type: Type): String = TypeSyntax.render(type, catalog::label)
    public fun typeOf(id: String): Type = TypeEncoding.decode(snapshot[id].type)
    public fun structureOf(id: String): Type = types.structure(typeOf(id))

    /** Live objects that are not part of another value: the entry points a person browses. */
    public fun roots(): List<String> = snapshot.objects.values
        .filter { !it.deleted && it.parent == null && it.type != TypeEncoding.DEFINITION_TYPE }
        .map { it.id }.sorted()

    public fun locate(path: String): String {
        val segments = path.split('.')
        var current = ObjectHandles.resolve(snapshot.objects.keys, segments.first())
        for (segment in segments.drop(1)) current = step(current, segment)
        return current
    }
    /**
     * The object and field that `set <path>` assigns. A path ending at a scalar register (a bare handle, a
     * variant payload or a map entry) assigns its `value` in place; otherwise the last segment names the field.
     */
    public fun field(path: String): Pair<String, String> {
        val whole = runCatching { locate(path) }.getOrNull()
        if (whole != null && isRegister(structureOf(whole))) return whole to "value"
        val cut = path.lastIndexOf('.')
        return if (cut < 0) locate(path) to "value" else locate(path.substring(0, cut)) to path.substring(cut + 1)
    }
    private fun isRegister(shape: Type): Boolean = shape !is Type.Record && shape !is Type.Sum && shape !is Type.Sequence && shape !is Type.MapOf && shape != Type.Scalar.TEXT
    /** The type a value assigned to [field] of [target] must have. */
    public fun fieldType(target: String, field: String): Type = when (val shape = structureOf(target)) {
        is Type.Record -> shape.fields[field] ?: throw IllegalArgumentException("${label(typeOf(target))} has no field $field")
        is Type.MapOf -> shape.value
        is Type.Sum, is Type.Sequence, Type.Scalar.TEXT -> throw IllegalArgumentException("A ${label(typeOf(target))} is edited with switch, insert/delete or edit-text")
        else -> { require(field == "value") { "${label(typeOf(target))} has no field $field" }; shape }
    }
    /** A list's live items, in order. */
    public fun items(id: String): List<String> = snapshot[id].children.filter { snapshot.objects[it]?.deleted == false }
    private fun step(id: String, segment: String): String {
        val obj = snapshot[id]
        fun instance(atom: Atom?, what: String): String = when (atom) {
            is Atom.Instance -> atom.id
            null -> throw IllegalArgumentException("No $what $segment")
            else -> throw IllegalArgumentException("$segment is a plain value, not an object; use set")
        }
        return when (val shape = structureOf(id)) {
            is Type.Record -> instance(obj.fields[segment], "field")
            is Type.MapOf -> {
                require(types.canonical(shape.key) == Type.Scalar.STRING) { "Structured map keys cannot appear in a path; use show" }
                instance(obj.fields[segment], "entry")
            }
            is Type.Sum -> {
                val variant = obj.fields["value"] as Atom.Variant
                require(variant.tag == segment) { "The active variant is ${variant.tag}, not $segment" }
                variant.payload ?: throw IllegalArgumentException("Variant $segment has no payload")
            }
            is Type.Sequence -> items(id).getOrNull(segment.toIntOrNull() ?: throw IllegalArgumentException("A list step is an index, not $segment"))
                ?: throw IllegalArgumentException("No list item $segment")
            else -> throw IllegalArgumentException("Cannot step into a ${label(typeOf(id))} with $segment")
        }
    }

    // ---- parsing ----

    private fun SyntaxReader.value(type: Type, depth: Int): Value {
        require(depth < 128) { "Value nesting exceeds limit" }
        return when (val shape = types.structure(type)) {
            Type.Any -> { val concrete = TypeSyntax.type(this, catalog); Value.Typed(concrete, value(concrete, depth + 1)) }
            Type.Scalar.TEXT -> Value.Text(string())
            is Type.Record -> Value.Record(record(shape) { value(it, depth + 1) })
            is Type.Sum -> variant(shape, { value(it, depth + 1) }) { tag, payload -> Value.Variant(tag, payload) }
            is Type.Sequence -> Value.Sequence(list { value(shape.element, depth + 1) })
            is Type.MapOf ->
                if (types.canonical(shape.key) == Type.Scalar.STRING) Value.MapEntries(entries({ name("a map key") }) { value(shape.value, depth + 1) })
                else Value.KeyedEntries(entries({ key(shape.key, depth + 1) }) { value(shape.value, depth + 1) })
            else -> Value.Atomic(atom(shape))
        }
    }
    private fun SyntaxReader.key(type: Type, depth: Int): KeyValue {
        require(depth < 128) { "Key nesting exceeds limit" }
        return when (val shape = types.structure(type)) {
            Type.Scalar.TEXT -> KeyValue.Atomic(Atom.Str(string()))
            // Like field names, string keys may be written bare.
            Type.Scalar.STRING -> KeyValue.Atomic(Atom.Str(name("a string key")))
            is Type.Record -> KeyValue.Record(record(shape) { key(it, depth + 1) })
            is Type.Sum -> variant(shape, { key(it, depth + 1) }) { tag, payload -> KeyValue.Variant(tag, payload) }
            is Type.Sequence -> KeyValue.Sequence(list { key(shape.element, depth + 1) })
            is Type.MapOf -> KeyValue.MapEntries(entries({ key(shape.key, depth + 1) }) { key(shape.value, depth + 1) })
            else -> KeyValue.Atomic(atom(shape))
        }
    }
    private fun SyntaxReader.atom(shape: Type): Atom = when (shape) {
        Type.Scalar.BOOLEAN -> when (val token = next()) {
            is Ident -> when (token.value) { "true" -> Atom.Bool(true); "false" -> Atom.Bool(false); else -> fail(token, "Expected true or false") }
            else -> fail(token, "Expected true or false")
        }
        Type.Scalar.NUMBER -> { val token = next(); Atom.Number((token as? Num ?: fail(token, "Expected a number")).value) }
        Type.Scalar.STRING -> Atom.Str(string())
        Type.Scalar.BLOB -> {
            val token = peek()
            when (ident("file(\"path\") or blob(...)")) {
                "file" -> { punct('('); val path = string(); punct(')'); loadBlob(path) }
                "blob" -> {
                    punct('('); val hash = string(); punct(',')
                    val sizeToken = next()
                    val size = (sizeToken as? Num ?: fail(sizeToken, "Expected the blob size")).value
                    if (size < 0 || size != kotlin.math.floor(size)) fail(sizeToken, "Expected a whole blob size")
                    punct(',')
                    val chunks = list { string() }
                    punct(')')
                    Atom.Blob(hash, size.toLong(), chunks)
                }
                else -> fail(token, "Expected file(\"path\") or blob(\"hash\", size, [chunks])")
            }
        }
        is Type.Enum -> {
            val token = peek()
            val choice = name("an enum choice")
            if (choice !in shape.choices) fail(token, "Expected one of ${shape.choices.sorted().joinToString()}")
            Atom.Str(choice)
        }
        is Type.Ref -> Atom.Ref(reference())
        else -> fail(peek(), "Cannot write a ${label(shape)} value here")
    }
    private fun SyntaxReader.string(): String { val token = next(); return (token as? Str ?: fail(token, "Expected a quoted string")).value }
    private fun SyntaxReader.reference(): String {
        val wrapped = isIdent("ref")
        if (wrapped) { next(); punct('(') }
        val token = next()
        val handle = (token as? Handle ?: fail(token, "Expected ref(@handle)")).value
        val id = runCatching { ObjectHandles.resolve(snapshot.objects.keys, handle) }.getOrElse { fail(token, it.message ?: "Unknown object") }
        if (wrapped) punct(')')
        return id
    }
    private fun <T> SyntaxReader.record(shape: Type.Record, item: (Type) -> T): Map<String, T> {
        val open = peek()
        punct('{')
        val fields = linkedMapOf<String, T>()
        if (!accept('}')) {
            do {
                if (isPunct('}')) break
                val token = peek()
                val name = name("a field name")
                val type = shape.fields[name] ?: fail(token, "Unknown field $name; expected ${shape.fields.keys.sorted().joinToString()}")
                if (name in fields) fail(token, "Field $name is given twice")
                punct(':')
                fields[name] = item(type)
            } while (accept(','))
            punct('}')
        }
        val missing = shape.fields.keys - fields.keys
        if (missing.isNotEmpty()) fail(open, "Missing field(s) ${missing.sorted().joinToString()}")
        return fields
    }
    private fun <T, V> SyntaxReader.variant(shape: Type.Sum, item: (Type) -> T, build: (String, T?) -> V): V {
        val token = peek()
        val tag = ident("a variant tag")
        if (!shape.variants.containsKey(tag)) fail(token, "Expected one of ${shape.variants.keys.sorted().joinToString()}")
        val payload = shape.variants[tag] ?: return build(tag, null).also { if (isPunct('(') || isPunct('{')) fail(peek(), "Variant $tag has no payload") }
        return when {
            isPunct('{') && types.structure(payload) is Type.Record -> build(tag, item(payload))
            accept('(') -> build(tag, item(payload)).also { punct(')') }
            else -> fail(peek(), "Variant $tag needs a payload: $tag(...)")
        }
    }
    private fun <T> SyntaxReader.list(item: () -> T): List<T> {
        punct('[')
        val items = mutableListOf<T>()
        if (!accept(']')) {
            do { if (isPunct(']')) break; items.add(item()) } while (accept(','))
            punct(']')
        }
        return items
    }
    private fun <K, V> SyntaxReader.entries(key: () -> K, value: () -> V): Map<K, V> {
        punct('{')
        val entries = linkedMapOf<K, V>()
        if (!accept('}')) {
            do {
                if (isPunct('}')) break
                val token = peek()
                val k = key()
                if (k in entries) fail(token, "Map key given twice")
                punct(':')
                entries[k] = value()
            } while (accept(','))
            punct('}')
        }
        return entries
    }

    // ---- rendering ----

    /** Renders an object so that [parse] against its type reads back an equal value. */
    public fun render(id: String): String = render(id, 0)
    private fun render(id: String, depth: Int): String {
        val obj = snapshot.objects[id] ?: return "<missing @${ObjectHandles.short(id)}>"
        if (obj.deleted) return "<deleted @${ObjectHandles.short(id)}>"
        require(depth < 128) { "Value nesting exceeds limit" }
        return when (val shape = structureOf(id)) {
            is Type.Record -> fields(shape.fields.keys.sorted().map { field -> SyntaxReader.nameOrQuoted(field) to atom(shape.fields.getValue(field), obj.fields[field], depth) })
            is Type.Sum -> {
                val variant = obj.fields["value"] as Atom.Variant
                val payloadType = shape.variants[variant.tag]
                val payload = variant.payload
                when {
                    payload == null || payloadType == null -> variant.tag
                    types.structure(payloadType) is Type.Record -> "${variant.tag} ${render(payload, depth + 1)}"
                    else -> "${variant.tag}(${slot(payloadType, payload, depth)})"
                }
            }
            is Type.Sequence -> items(id).joinToString(", ", "[", "]") { slot(shape.element, it, depth) }
            is Type.MapOf -> {
                val stringKeys = types.canonical(shape.key) == Type.Scalar.STRING
                fields(obj.fields.entries
                    .filter { (_, atom) -> atom !is Atom.Instance || snapshot.objects[atom.id]?.deleted == false }
                    .sortedBy { it.key }
                    .map { (field, atom) ->
                        val key = if (stringKeys) SyntaxReader.nameOrQuoted(field) else renderKey(KeyEncoding.fromField(types, shape.key, field), shape.key)
                        key to atom(shape.value, atom, depth)
                    })
            }
            Type.Scalar.TEXT -> SyntaxReader.quote(obj.text)
            else -> atom(shape, obj.fields["value"], depth)
        }
    }
    /** An embedded value; in an `Any` slot it is prefixed with its concrete type, as it is written. */
    private fun slot(expected: Type, id: String, depth: Int): String {
        val value = render(id, depth + 1)
        val obj = snapshot.objects[id]
        return if (types.canonical(expected) == Type.Any && obj != null && !obj.deleted) "${label(typeOf(id))} $value" else value
    }
    private fun fields(entries: List<Pair<String, String>>): String =
        if (entries.isEmpty()) "{}" else entries.joinToString(", ", "{ ", " }") { (key, value) -> "$key: $value" }
    private fun atom(expected: Type, atom: Atom?, depth: Int): String = when (atom) {
        null -> "<missing>"
        is Atom.Instance -> slot(expected, atom.id, depth)
        is Atom.Ref -> "ref(@${ObjectHandles.short(atom.id)})"
        is Atom.Bool -> atom.value.toString()
        is Atom.Number -> number(atom.value)
        is Atom.Str -> if (types.canonical(expected) is Type.Enum) SyntaxReader.nameOrQuoted(atom.value) else SyntaxReader.quote(atom.value)
        is Atom.Blob -> "blob(${SyntaxReader.quote(atom.hash)}, ${atom.size}, ${atom.chunks.joinToString(", ", "[", "]") { SyntaxReader.quote(it) }})"
        is Atom.Variant -> atom.tag
    }
    private fun renderKey(key: KeyValue, type: Type): String = when (val shape = types.structure(type)) {
        is Type.Record -> fields(shape.fields.keys.sorted().map { SyntaxReader.nameOrQuoted(it) to renderKey((key as KeyValue.Record).fields.getValue(it), shape.fields.getValue(it)) })
        is Type.Sum -> {
            val variant = key as KeyValue.Variant
            val payloadType = shape.variants[variant.tag]
            when {
                variant.payload == null || payloadType == null -> variant.tag
                types.structure(payloadType) is Type.Record -> "${variant.tag} ${renderKey(variant.payload, payloadType)}"
                else -> "${variant.tag}(${renderKey(variant.payload, payloadType)})"
            }
        }
        is Type.Sequence -> (key as KeyValue.Sequence).items.joinToString(", ", "[", "]") { renderKey(it, shape.element) }
        is Type.MapOf -> fields((key as KeyValue.MapEntries).entries.map { (k, v) -> renderKey(k, shape.key) to renderKey(v, shape.value) }.sortedBy { it.first })
        else -> {
            val atom = (key as KeyValue.Atomic).atom
            if (atom is Atom.Str && shape !is Type.Enum) SyntaxReader.quote(atom.value) else atom(shape, atom, 0)
        }
    }
    private fun number(value: Double): String = when {
        value == 0.0 && 1.0 / value < 0 -> "-0.0"
        value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15 -> value.toLong().toString()
        else -> value.toString()
    }
}
