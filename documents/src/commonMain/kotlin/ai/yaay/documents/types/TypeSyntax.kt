package ai.yaay.documents.types

import ai.yaay.documents.types.SyntaxReader.Handle
import ai.yaay.documents.types.SyntaxReader.Ident
import ai.yaay.documents.types.SyntaxReader.Punct
import ai.yaay.documents.types.SyntaxReader.End

/** A named definition to publish. The name is display metadata; the definition gets a fresh nominal identity. */
public data class TypeDeclaration(public val name: String, public val definition: TypeDefinition)

/**
 * Human-readable type declarations over the compact [TypeEncoding]:
 *
 * ```
 * type Person = { name: String, bio: Text, tags: List<String>, manager: Ref<Person> }
 * type Status = Active | Suspended { reason: Text } | Archived(Number)
 * type Pair<A, B> = { first: A, second: B }
 * alias Tags = List<String>
 * ```
 *
 * Scalars: `Boolean`, `Number`, `String`, `Text` (collaborative), `Blob`. Constructors: `List<T>`,
 * `Map<V>` (string keys), `Map<K, V>`, `Ref<T>`, `enum(a, b)`, records `{ field: T }` and sums
 * `Tag | Tag { ... } | Tag(T)`; a single-variant sum is written with a leading `|`. `Any` is a slot for an
 * embedded value of any type (a document's content). Built-in `Node`, `Folder` and `Document` form the
 * workspace tree. A published type is
 * named by its name, or `Name@handle` when several versions share that name.
 */
public object TypeSyntax {
    private val scalars = mapOf(
        "Boolean" to Type.Scalar.BOOLEAN, "Number" to Type.Scalar.NUMBER, "String" to Type.Scalar.STRING,
        "Text" to Type.Scalar.TEXT, "Blob" to Type.Scalar.BLOB,
    )
    private val constructors = setOf("List", "Map", "Ref", "enum", "Any")
    private val keywords = setOf("type", "alias")
    /** Built-in type names cannot be published by users, so they always mean the built-in. */
    internal val reserved: Set<String> = scalars.keys + constructors + keywords + ai.yaay.documents.types.builtin.BuiltInTypes.all.map { it.name }

    /**
     * Parses a declaration file. Declarations may refer to each other in any order, including recursively;
     * [localId] supplies the identity each will be published under, in declaration order. A local name
     * shadows a published one, which is how a new version of a type is declared.
     */
    public fun parseDeclarations(text: String, catalog: TypeCatalog, localId: (Int) -> String): List<TypeDeclaration> {
        val reader = SyntaxReader(text)
        val locals = headers(reader)
        val declarations = mutableListOf<TypeDeclaration>()
        while (!reader.atEnd()) {
            val keywordToken = reader.peek()
            val keyword = reader.ident("'type' or 'alias'")
            if (keyword !in keywords) reader.fail(keywordToken, "Expected 'type' or 'alias'")
            val name = reader.ident("a type name")
            val parameters = if (reader.accept('<')) commaSeparated(reader, '>') { reader.ident("a type parameter") } else emptyList()
            reader.punct('=')
            val body = Parser(reader, catalog, locals.mapValues { (name, header) -> localId(header.index) to header.arity }, parameters.toSet()).type()
            reader.accept(';')
            declarations.add(TypeDeclaration(name, TypeDefinition(parameters, keyword == "alias", body)))
        }
        require(declarations.isNotEmpty()) { "No type declarations found" }
        return declarations
    }

    /** Parses one type expression, for example the type of a value to create. */
    public fun parseType(text: String, catalog: TypeCatalog): Type {
        val reader = SyntaxReader(text)
        return Parser(reader, catalog, emptyMap(), emptySet()).type().also { reader.expectEnd() }
    }

    /** Reads one type expression from within a larger text, e.g. the type prefix of an `Any` value. */
    internal fun type(reader: SyntaxReader, catalog: TypeCatalog): Type = Parser(reader, catalog, emptyMap(), emptySet()).type()

    public fun render(declaration: TypeDeclaration, label: (String) -> String): String {
        val definition = declaration.definition
        val parameters = if (definition.parameters.isEmpty()) "" else definition.parameters.joinToString(", ", "<", ">")
        return "${if (definition.alias) "alias" else "type"} ${declaration.name}$parameters = ${render(definition.body, label)}"
    }

    /** Renders [type] so that [parseType] reads it back; [label] names published definitions. */
    public fun render(type: Type, label: (String) -> String): String = when (type) {
        is Type.Scalar -> scalars.entries.first { it.value == type }.key
        is Type.Enum -> type.choices.sorted().joinToString(", ", "enum(", ")") { SyntaxReader.nameOrQuoted(it) }
        is Type.Record -> record(type, label)
        is Type.Sum -> (if (type.variants.size == 1) "| " else "") + type.variants.entries.joinToString(" | ") { (tag, payload) ->
            when (payload) {
                null -> tag
                is Type.Record -> "$tag ${record(payload, label)}"
                else -> "$tag(${render(payload, label)})"
            }
        }
        is Type.Sequence -> "List<${render(type.element, label)}>"
        is Type.MapOf -> if (type.key == Type.Scalar.STRING) "Map<${render(type.value, label)}>" else "Map<${render(type.key, label)}, ${render(type.value, label)}>"
        is Type.Ref -> "Ref<${render(type.target, label)}>"
        is Type.Named -> label(type.id) + if (type.arguments.isEmpty()) "" else type.arguments.joinToString(", ", "<", ">") { render(it, label) }
        is Type.Parameter -> type.name
        Type.Any -> "Any"
    }
    private fun record(type: Type.Record, label: (String) -> String): String =
        if (type.fields.isEmpty()) "{}" else type.fields.entries.joinToString(", ", "{ ", " }") { (name, field) -> "${SyntaxReader.nameOrQuoted(name)}: ${render(field, label)}" }

    private data class Header(val index: Int, val arity: Int)
    /** First pass: every declared name and arity, so bodies can refer forward and recursively. */
    private fun headers(reader: SyntaxReader): Map<String, Header> {
        val headers = linkedMapOf<String, Header>()
        var depth = 0
        var i = 0
        while (i < reader.tokens.size) {
            val token = reader.tokens[i]
            if (token is Punct) when (token.value) { '{', '(', '[', '<' -> depth++; '}', ')', ']', '>' -> depth-- }
            if (depth == 0 && token is Ident && token.value in keywords) {
                val name = reader.tokens.getOrNull(i + 1) as? Ident ?: reader.fail(reader.tokens.getOrElse(i + 1) { token }, "Expected a type name")
                if (name.value in reserved) reader.fail(name, "'${name.value}' is reserved")
                if (name.value in headers) reader.fail(name, "Type ${name.value} is declared twice")
                var arity = 0
                if ((reader.tokens.getOrNull(i + 2) as? Punct)?.value == '<') {
                    var j = i + 3
                    while (reader.tokens.getOrNull(j).let { it is Ident || (it as? Punct)?.value == ',' }) { if (reader.tokens[j] is Ident) arity++; j++ }
                }
                headers[name.value] = Header(headers.size, arity)
            }
            i++
        }
        return headers
    }

    private fun <T> commaSeparated(reader: SyntaxReader, close: Char, item: () -> T): List<T> {
        val items = mutableListOf<T>()
        if (!reader.accept(close)) {
            do { if (reader.isPunct(close)) break; items.add(item()) } while (reader.accept(','))
            reader.punct(close)
        }
        return items
    }

    private class Parser(
        val reader: SyntaxReader,
        val catalog: TypeCatalog,
        val locals: Map<String, Pair<String, Int>>,
        val parameters: Set<String>,
    ) {
        fun type(): Type = if (reader.isPunct('|') || sumAhead()) sum() else primary()

        /** A top-level `|` before this expression ends makes it a sum of tagged alternatives. */
        private fun sumAhead(): Boolean {
            var depth = 0
            var i = reader.index
            while (true) {
                val token = reader.tokens[i]
                when {
                    token is End -> return false
                    token is Ident && depth == 0 && token.value in keywords -> return false
                    token is Punct -> when (token.value) {
                        '{', '(', '[', '<' -> depth++
                        '}', ')', ']', '>' -> { if (depth == 0) return false; depth-- }
                        ',', ';', '=' -> if (depth == 0) return false
                        '|' -> if (depth == 0) return true
                    }
                }
                i++
            }
        }

        private fun sum(): Type.Sum {
            reader.accept('|')
            val variants = linkedMapOf<String, Type?>()
            do {
                val tagToken = reader.peek()
                val tag = reader.ident("a variant tag")
                if (tag in variants) reader.fail(tagToken, "Variant $tag is declared twice")
                variants[tag] = when {
                    reader.isPunct('{') -> record()
                    reader.accept('(') -> type().also { reader.punct(')') }
                    else -> null
                }
            } while (reader.accept('|'))
            return Type.Sum(variants)
        }

        private fun record(): Type.Record {
            reader.punct('{')
            val fields = linkedMapOf<String, Type>()
            commaSeparated(reader, '}') {
                val nameToken = reader.peek()
                val name = reader.name("a field name")
                if (name in fields) reader.fail(nameToken, "Field $name is declared twice")
                reader.punct(':')
                fields[name] = type()
            }
            return Type.Record(fields)
        }

        private fun arguments(): List<Type> = if (reader.accept('<')) commaSeparated(reader, '>') { type() } else emptyList()

        private fun primary(): Type {
            if (reader.isPunct('{')) return record()
            if (reader.accept('(')) return type().also { reader.punct(')') }
            val token = reader.next()
            if (token is Handle) return named(catalog.resolve(null, token.value).let { it.id to it.definition.parameters.size }, token)
            val name = (token as? Ident ?: reader.fail(token, "Expected a type")).value
            scalars[name]?.let { return it }
            return when (name) {
                "Any" -> Type.Any
                "List" -> Type.Sequence(single(token, "List"))
                "Ref" -> Type.Ref(single(token, "Ref"))
                "Map" -> {
                    val args = arguments()
                    when (args.size) {
                        1 -> Type.MapOf(args[0])
                        2 -> Type.MapOf(args[1], args[0])
                        else -> reader.fail(token, "Map takes <Value> or <Key, Value>")
                    }
                }
                "enum" -> {
                    reader.punct('(')
                    val choices = commaSeparated(reader, ')') { reader.name("an enum choice") }
                    if (choices.isEmpty()) reader.fail(token, "An enum needs at least one choice")
                    if (choices.distinct().size != choices.size) reader.fail(token, "Enum choices must be distinct")
                    Type.Enum(choices.toSet())
                }
                in parameters -> Type.Parameter(name)
                else -> {
                    val handle = reader.peek() as? Handle
                    when {
                        handle != null -> { reader.next(); named(catalog.resolve(name, handle.value).let { it.id to it.definition.parameters.size }, token) }
                        name in locals -> named(locals.getValue(name), token)
                        else -> named(runCatching { catalog.resolve(name) }.getOrElse { reader.fail(token, it.message ?: "Unknown type $name") }.let { it.id to it.definition.parameters.size }, token)
                    }
                }
            }
        }

        private fun single(token: SyntaxReader.Token, constructor: String): Type {
            val args = arguments()
            if (args.size != 1) reader.fail(token, "$constructor takes exactly one type argument")
            return args[0]
        }

        private fun named(target: Pair<String, Int>, token: SyntaxReader.Token): Type.Named {
            val args = arguments()
            if (args.size != target.second) reader.fail(token, "Expected ${target.second} type argument(s), found ${args.size}")
            return Type.Named(target.first, args)
        }
    }
}
