package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*

/** Complete form values. Drafts remain local until their entire edit batch validates. */
public sealed interface Value {
    public data class Atomic(public val atom: Atom) : Value
    public data class Record(public val fields: Map<String, Value>) : Value
    public data class Variant(public val tag: String, public val payload: Value? = null) : Value
    public data class Sequence(public val items: List<Value>) : Value
    public data class MapEntries(public val entries: Map<String, Value>) : Value
    public data class KeyedEntries(public val entries: Map<KeyValue, Value>) : Value
    public data class Text(public val text: String) : Value
}

/** Compiles typed edits into one atomic batch inside the replica writer's commit callback. */
public class TypedEdit(private val id: (Int) -> String, private val before: Snapshot) {
    private val types: TypeSystem = TypeSystem(before)
    private val staged: MutableList<Operation> = mutableListOf()
    public val operations: List<Operation> get() = staged.toList()
    private fun create(type: Type, shape: Shape, fields: Map<String, Atom> = emptyMap()): String {
        val instance = id(staged.size)
        staged.add(Operation.Create(instance, TypeEncoding.encode(type), shape, fields))
        return instance
    }
    private fun slot(type: Type, value: Value): Atom = when (types.canonical(type)) {
        Type.Scalar.BOOLEAN, Type.Scalar.NUMBER, Type.Scalar.STRING, Type.Scalar.BLOB, is Type.Ref, is Type.Enum ->
            (value as? Value.Atomic)?.atom ?: throw IllegalArgumentException("Expected atomic value")
        else -> Atom.Instance(create(type, value))
    }
    public fun create(type: Type, value: Value): String = when (val shape = types.structure(type)) {
        is Type.Record -> {
            require(value is Value.Record && value.fields.keys == shape.fields.keys)
            val fields = shape.fields.mapValues { (field, fieldType) -> slot(fieldType, value.fields.getValue(field)) }
            create(type, Shape.RECORD, fields)
        }
        is Type.Sum -> {
            require(value is Value.Variant && shape.variants.containsKey(value.tag))
            val payloadType = shape.variants[value.tag]
            val payload = if (payloadType == null) { require(value.payload == null); null } else create(payloadType, value.payload ?: throw IllegalArgumentException("Missing payload"))
            create(type, Shape.SUM, mapOf("value" to Atom.Variant(value.tag, payload)))
        }
        is Type.Sequence -> {
            require(value is Value.Sequence)
            val list = create(type, Shape.LIST)
            var previous: String? = null
            value.items.forEach {
                val item = create(shape.element, it)
                staged.add(Operation.Place(item, list, previous))
                previous = item
            }
            list
        }
        is Type.MapOf -> {
            val values = when (value) {
                is Value.MapEntries -> { require(types.canonical(shape.key) == Type.Scalar.STRING); value.entries }
                is Value.KeyedEntries -> {
                    val entries = value.entries.map { KeyEncoding.toField(types, shape.key, it.key) to it.value }
                    require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate canonical map key" }
                    entries.toMap()
                }
                else -> throw IllegalArgumentException("Expected map entries")
            }
            val entries = values.mapValues { Atom.Instance(create(shape.value, it.value)) }
            create(type, Shape.MAP, entries)
        }
        Type.Scalar.TEXT -> {
            require(value is Value.Text)
            val text = create(type, Shape.TEXT)
            if (value.text.isNotEmpty()) staged.add(Operation.EditText(text, null, value.text))
            text
        }
        else -> {
            require(value is Value.Atomic)
            create(type, Shape.REGISTER, mapOf("value" to value.atom))
        }
    }
    /** A structured replacement always creates a new instance and selects it atomically. */
    public fun assign(target: String, field: String, value: Value) {
        val shape = types.structure(TypeEncoding.decode(before[target].type))
        val expected = when (shape) {
            is Type.Record -> shape.fields[field] ?: throw IllegalArgumentException("Unknown field")
            is Type.MapOf -> {
                staged.add(Operation.Assign(target, field, Atom.Instance(create(shape.value, value))))
                return
            }
            else -> { require(field == "value"); shape }
        }
        require(shape !is Type.Sum && shape !is Type.Sequence && shape != Type.Scalar.TEXT)
        staged.add(Operation.Assign(target, field, slot(expected, value)))
    }
    public fun put(map: String, key: KeyValue, value: Value) {
        val shape = types.structure(TypeEncoding.decode(before[map].type)) as? Type.MapOf ?: throw IllegalArgumentException("Not a map")
        assign(map, KeyEncoding.toField(types, shape.key, key), value)
    }
    public fun switch(target: String, variant: Value.Variant) {
        val shape = types.structure(TypeEncoding.decode(before[target].type)) as? Type.Sum ?: throw IllegalArgumentException("Not a sum")
        require(shape.variants.containsKey(variant.tag))
        val expected = shape.variants[variant.tag]
        val payload = if (expected == null) { require(variant.payload == null); null } else create(expected, variant.payload ?: throw IllegalArgumentException("Missing payload"))
        staged.add(Operation.Assign(target, "value", Atom.Variant(variant.tag, payload)))
    }
    public fun insert(list: String, after: String?, value: Value): String {
        val shape = types.structure(TypeEncoding.decode(before[list].type)) as? Type.Sequence ?: throw IllegalArgumentException("Not a list")
        val item = create(shape.element, value)
        staged.add(Operation.Place(item, list, after))
        return item
    }
    public fun move(item: String, destination: String?, after: String? = null) { staged.add(Operation.Place(item, destination, after)) }
    public fun delete(target: String) { staged.add(Operation.Delete(target)) }
    /** Indices address Unicode scalars in the transaction's initial snapshot. */
    public fun editText(target: String, start: Int, deleteCount: Int, insert: String) {
        val text = before[target]
        require(text.shape == Shape.TEXT && start in 0..text.textPositions.size && deleteCount in 0..(text.textPositions.size - start))
        staged.add(Operation.EditText(target, text.textPositions.getOrNull(start - 1), insert, text.textPositions.subList(start, start + deleteCount).toSet()))
    }
}

/** Generic representation is available independently of any specialized renderer. */
public sealed interface GenericValue {
    public data class Object(public val id: String, public val type: String, public val fields: Map<String, GenericValue>) : GenericValue
    public data class Atomic(public val value: Atom) : GenericValue
    public data class Sequence(public val items: List<GenericValue>) : GenericValue
    public data class Text(public val text: String) : GenericValue
    public data class Variant(public val tag: String, public val payload: GenericValue?) : GenericValue
    public data class Reference(public val id: String, public val status: ReferenceStatus) : GenericValue
    public data class Deleted(public val id: String) : GenericValue
    public data class Missing(public val id: String) : GenericValue
}
public enum class ReferenceStatus { AVAILABLE, DELETED, MISSING }

public fun Snapshot.genericValue(id: String): GenericValue {
    fun objectValue(id: String, path: Set<String>): GenericValue {
        val obj = objects[id] ?: return GenericValue.Missing(id)
        if (obj.deleted) return GenericValue.Deleted(id)
        require(id !in path) { "Embedded value cycle" }
        val next = path + id
        fun atom(value: Atom): GenericValue = when (value) {
            is Atom.Instance -> objectValue(value.id, next)
            is Atom.Ref -> GenericValue.Reference(value.id, when { value.id !in objects -> ReferenceStatus.MISSING; get(value.id).deleted -> ReferenceStatus.DELETED; else -> ReferenceStatus.AVAILABLE })
            is Atom.Variant -> GenericValue.Variant(value.tag, value.payload?.let { objectValue(it, next) })
            else -> GenericValue.Atomic(value)
        }
        return when (obj.shape) {
            Shape.TEXT -> GenericValue.Text(obj.text)
            Shape.LIST -> GenericValue.Sequence(obj.children.map { objectValue(it, next) })
            else -> GenericValue.Object(id, obj.type, obj.fields.filterValues { value -> obj.shape != Shape.MAP || value !is Atom.Instance || objects[value.id]?.deleted != true }.mapValues { atom(it.value) })
        }
    }
    return objectValue(id, emptySet())
}
