package ai.yaay.documents.types

import ai.yaay.crdt.*

/** Type definitions are immutable CRDT objects. Names refer to their exact object identity. */
public sealed interface Type {
    public enum class Scalar : Type { BOOLEAN, NUMBER, STRING, TEXT, BLOB }
    public data class Enum(public val choices: Set<String>) : Type
    public data class Record(public val fields: Map<String, Type>) : Type
    public data class Sum(public val variants: Map<String, Type?>) : Type
    public data class Sequence(public val element: Type) : Type
    public data class MapOf(public val value: Type, public val key: Type = Scalar.STRING) : Type
    public data class Ref(public val target: Type) : Type
    public data class Named(public val id: String, public val arguments: List<Type> = emptyList()) : Type
    public data class Parameter(public val name: String) : Type
}

public data class TypeDefinition(public val parameters: List<String>, public val alias: Boolean, public val body: Type)

/** A small length-prefixed, platform-independent grammar; no arbitrary executable type behavior. */
public object TypeEncoding {
    public const val DEFINITION_TYPE: String = "yaay:type-definition:1"
    private fun token(s: String): String = "${s.length}:$s"
    public fun encode(type: Type): String = when (type) {
        is Type.Scalar -> "s" + token(type.name)
        is Type.Enum -> "e${type.choices.size}:" + type.choices.sorted().joinToString("") { token(it) }
        is Type.Record -> "p${type.fields.size}:" + type.fields.entries.sortedBy { it.key }.joinToString("") { token(it.key) + encode(it.value) }
        is Type.Sum -> "u${type.variants.size}:" + type.variants.entries.sortedBy { it.key }.joinToString("") { token(it.key) + if (it.value == null) "0" else "1" + encode(it.value!!) }
        is Type.Sequence -> "l" + encode(type.element)
        is Type.MapOf -> if (type.key == Type.Scalar.STRING) "m" + encode(type.value) else "k" + encode(type.key) + encode(type.value)
        is Type.Ref -> "r" + encode(type.target)
        is Type.Named -> "n" + token(type.id) + "${type.arguments.size}:" + type.arguments.joinToString("") { encode(it) }
        is Type.Parameter -> "v" + token(type.name)
    }
    public fun encode(definition: TypeDefinition): String = (if (definition.alias) "a" else "n") + "${definition.parameters.size}:" + definition.parameters.joinToString("") { token(it) } + encode(definition.body)
    private class Reader(val value: String) {
        var at = 0
        fun char(): Char { require(at < value.length); return value[at++] }
        fun count(): Int {
            val end = value.indexOf(':', at)
            require(end > at)
            val count = value.substring(at, end).toInt()
            require(count in 0..100_000)
            at = end + 1
            return count
        }
        fun token(): String { val n = count(); require(at + n <= value.length); return value.substring(at, at + n).also { at += n } }
        fun type(depth: Int = 0): Type {
            require(depth < 128) { "Type nesting exceeds limit" }
            return when (char()) {
                's' -> Type.Scalar.valueOf(token())
                'e' -> Type.Enum(List(count()) { token() }.toSet().also { require(it.isNotEmpty()) })
                'p' -> { val n = count(); Type.Record(buildMap { repeat(n) { val key = token(); require(put(key, type(depth + 1)) == null) } }) }
                'u' -> { val n = count(); Type.Sum(buildMap { repeat(n) { val key = token(); require(!containsKey(key)); put(key, when(char()) { '0' -> null; '1' -> type(depth + 1); else -> error("Invalid sum") }) } }) }
                'l' -> Type.Sequence(type(depth + 1))
                'm' -> Type.MapOf(type(depth + 1))
                'k' -> { val key = type(depth + 1); Type.MapOf(type(depth + 1), key) }
                'r' -> Type.Ref(type(depth + 1))
                'n' -> Type.Named(token(), List(count()) { type(depth + 1) })
                'v' -> Type.Parameter(token())
                else -> throw IllegalArgumentException("Unknown type expression")
            }
        }
    }
    public fun decode(value: String): Type = Reader(value).run { type().also { require(at == value.length && encode(it) == value) } }
    public fun definition(value: String): TypeDefinition = Reader(value).run {
        val alias = when (char()) { 'a' -> true; 'n' -> false; else -> throw IllegalArgumentException("Invalid definition") }
        val parameters = List(count()) { token() }
        require(parameters.distinct().size == parameters.size)
        TypeDefinition(parameters, alias, type()).also { require(at == value.length && encode(it) == value) }
    }
    public fun publish(id: String, definition: TypeDefinition): Operation.Create = Operation.Create(id, DEFINITION_TYPE, Shape.REGISTER, mapOf("value" to Atom.Str(encode(definition))))
}

/** Resolves pinned definitions from the author's CRDT snapshot, never a separate mutable registry. */
public class TypeSystem(private val snapshot: Snapshot) {
    private fun definition(id: String): TypeDefinition {
        val obj = snapshot[id]
        require(obj.type == TypeEncoding.DEFINITION_TYPE && !obj.deleted) { "Unknown published type $id" }
        return TypeEncoding.definition((obj.fields["value"] as? Atom.Str)?.value ?: throw IllegalArgumentException("Malformed type definition"))
    }
    private fun substitute(type: Type, bindings: Map<String, Type>): Type = when (type) {
        is Type.Parameter -> bindings[type.name] ?: throw IllegalArgumentException("Unbound parameter ${type.name}")
        is Type.Record -> Type.Record(type.fields.mapValues { substitute(it.value, bindings) })
        is Type.Sum -> Type.Sum(type.variants.mapValues { it.value?.let { value -> substitute(value, bindings) } })
        is Type.Sequence -> Type.Sequence(substitute(type.element, bindings))
        is Type.MapOf -> Type.MapOf(substitute(type.value, bindings), substitute(type.key, bindings))
        is Type.Ref -> Type.Ref(substitute(type.target, bindings))
        is Type.Named -> type.copy(arguments = type.arguments.map { substitute(it, bindings) })
        is Type.Scalar, is Type.Enum -> type
    }
    private fun body(type: Type.Named): Type {
        val def = definition(type.id)
        require(def.parameters.size == type.arguments.size) { "Generic arity mismatch" }
        return substitute(def.body, def.parameters.zip(type.arguments).toMap())
    }
    /** Normalize aliases throughout a type, retaining every nominal named identity. */
    public fun canonical(type: Type): Type = canonical(type, emptySet(), 0)
    private fun canonical(type: Type, aliases: Set<String>, depth: Int): Type {
        require(depth < 128) { "Unbounded type expansion" }
        fun recur(t: Type): Type = canonical(t, aliases, depth + 1)
        return when (type) {
            is Type.Named -> {
                val def = definition(type.id)
                require(def.parameters.size == type.arguments.size)
                if (def.alias) {
                    require(type.id !in aliases) { "Alias cycle at ${type.id}" }
                    canonical(body(type), aliases + type.id, depth + 1)
                } else type.copy(arguments = type.arguments.map(::recur))
            }
            is Type.Record -> Type.Record(type.fields.mapValues { recur(it.value) })
            is Type.Sum -> Type.Sum(type.variants.mapValues { it.value?.let(::recur) })
            is Type.Sequence -> Type.Sequence(recur(type.element))
            is Type.MapOf -> Type.MapOf(recur(type.value), recur(type.key))
            is Type.Ref -> Type.Ref(recur(type.target))
            is Type.Parameter -> throw IllegalArgumentException("Unbound type parameter")
            is Type.Scalar, is Type.Enum -> type
        }
    }
    public fun referenceMatches(id: String, expected: Type): Boolean = assignable(TypeEncoding.decode(snapshot[id].type), expected)
    public fun assignable(actual: Type, expected: Type): Boolean = canonical(actual) == canonical(expected)
    public fun structure(type: Type, visited: Set<String> = emptySet()): Type = when (val t = canonical(type)) {
        is Type.Named -> { require(t.id !in visited) { "Nominal type has no structural body" }; structure(body(t), visited + t.id) }
        else -> t
    }
    public fun validateDefinitions() {
        snapshot.objects.values.filter { it.type == TypeEncoding.DEFINITION_TYPE }.forEach { obj ->
            val def = definition(obj.id)
            val bindings = def.parameters.associateWith { Type.Scalar.BOOLEAN }
            val concrete = substitute(def.body, bindings)
            canonical(concrete)
            structure(Type.Named(obj.id, def.parameters.map { Type.Scalar.BOOLEAN }))
        }
    }
    public fun validateObject(obj: ResolvedObject) {
        if (obj.type == TypeEncoding.DEFINITION_TYPE || obj.deleted) return
        val type = TypeEncoding.decode(obj.type)
        val shape = structure(type)
        fun value(atom: Atom, expected: Type) {
            when (val t = canonical(expected)) {
                is Type.Ref -> {
                    require(atom is Atom.Ref) { "Reference requires an explicit reference value" }
                    require(assignable(TypeEncoding.decode(snapshot[atom.id].type), t.target)) { "Wrong referenced type" }
                }
                is Type.Enum -> require(atom is Atom.Str && atom.value in t.choices)
                Type.Scalar.BOOLEAN -> require(atom is Atom.Bool)
                Type.Scalar.NUMBER -> require(atom is Atom.Number && atom.value.isFinite())
                Type.Scalar.STRING -> require(atom is Atom.Str)
                Type.Scalar.BLOB -> require(atom is Atom.Blob)
                else -> {
                    require(atom is Atom.Instance) { "Embedded structured value requires an instance" }
                    require(assignable(TypeEncoding.decode(snapshot[atom.id].type), t)) { "Embedded type mismatch" }
                }
            }
        }
        when (shape) {
            is Type.Record -> {
                require(obj.shape == Shape.RECORD && obj.fields.keys == shape.fields.keys) { "Record fields do not match published structure" }
                shape.fields.forEach { (field, expected) -> value(obj.fields.getValue(field), expected) }
            }
            is Type.Sum -> {
                require(obj.shape == Shape.SUM && obj.fields.keys == setOf("value"))
                val variant = obj.fields["value"] as? Atom.Variant ?: throw IllegalArgumentException("Expected variant")
                require(shape.variants.containsKey(variant.tag))
                val payload = shape.variants[variant.tag]
                if (payload == null) require(variant.payload == null) else {
                    val target = variant.payload ?: throw IllegalArgumentException("Missing variant payload")
                    require(assignable(TypeEncoding.decode(snapshot[target].type), payload))
                }
            }
            is Type.Sequence -> {
                require(obj.shape == Shape.LIST && obj.fields.isEmpty())
                obj.children.forEach { require(assignable(TypeEncoding.decode(snapshot[it].type), shape.element)) }
            }
            is Type.MapOf -> {
                require(obj.shape == Shape.MAP)
                // Keys are immutable typed selectors; values remain independently identified CRDTs.
                obj.fields.keys.forEach { KeyEncoding.fromField(this, shape.key, it) }
                obj.fields.values.forEach { atom ->
                    require(atom is Atom.Instance)
                    require(assignable(TypeEncoding.decode(snapshot[atom.id].type), shape.value))
                }
            }
            Type.Scalar.TEXT -> require(obj.shape == Shape.TEXT && obj.fields.isEmpty())
            else -> {
                require(obj.shape == Shape.REGISTER && obj.fields.keys == setOf("value"))
                // Nominal scalar wrappers validate their payload against their structural scalar.
                value(obj.fields.getValue("value"), shape)
            }
        }
    }
}

/** Shared public mutation boundary for local changes and verified incoming author snapshots. */
public class TypedMutationValidator : MutationValidator {
    override fun validate(author: String, before: Snapshot, after: Snapshot, operations: List<Operation>) {
        val fresh = operations.filterIsInstance<Operation.Create>().map { it.id }.toSet()
        val attachments = mutableMapOf<String, Pair<String, String>>()
        fun attachment(owner: String, field: String, value: Atom) {
            val child = when (value) { is Atom.Instance -> value.id; is Atom.Variant -> value.payload; else -> null } ?: return
            val unchanged = before.objects[owner]?.fields?.get(field) == value
            require(child in fresh || unchanged) { "Embedded selection requires a fresh value instance; use Ref for existing content" }
            val slot = owner to field
            require(attachments.put(child, slot).let { it == null || it == slot }) { "An embedded instance cannot be shared between slots" }
        }
        operations.forEach { op ->
            when (op) {
                is Operation.Create -> op.fields.forEach { (field, value) -> attachment(op.id, field, value) }
                is Operation.Assign -> attachment(op.target, op.field, op.value)
                is Operation.Place -> require(after[op.target].embeddedOwner == null) { "Embedded values cannot be independently placed; move their owning block" }
                else -> Unit
            }
        }
        val immutable = before.objects.values.filter { it.type == TypeEncoding.DEFINITION_TYPE }.map { it.id }.toSet()
        operations.forEach { op ->
            val target = when (op) {
                is Operation.Assign -> op.target
                is Operation.Delete -> op.target
                is Operation.Place -> op.target
                is Operation.EditText -> op.target
                else -> null
            }
            require(target !in immutable) { "Published type definitions are immutable" }
        }
        after.objects.values.filter { it.type == TypeEncoding.DEFINITION_TYPE }.forEach {
            require(it.shape == Shape.REGISTER && it.fields.keys == setOf("value") && !it.deleted)
        }
        val types = TypeSystem(after)
        types.validateDefinitions()
        after.objects.values.forEach(types::validateObject)
    }
}
