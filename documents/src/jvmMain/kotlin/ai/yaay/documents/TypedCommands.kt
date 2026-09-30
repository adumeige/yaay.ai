package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Headless-peer commands for typed content; every edit goes through the ordinary typed mutation boundary. */
internal object TypedCommands {
    val commands: Set<String> = setOf("define", "types", "create", "show", "set", "put", "insert", "switch", "delete", "move")
    val usage: String = """
        define <file | ->                     publish type declarations (see TypeSyntax)
        types                                 list published types
        create <type> <value>                 create a typed object
        show [path]                           show root objects, or one object
        set <path.field> <value>              assign a record field, map entry or register value
        put <map path> <key> <value>          assign a map entry (any key type)
        insert <list path> <value> [first | <item path>]    append, or insert at a position
        switch <sum path> <variant>           select a variant
        delete <path>                         delete an object, list item or map entry value
        move <item path> <list path | root> [first | <item path>]
    """.trimIndent()

    fun run(command: String, rest: List<String>, replica: DurableReplica, blobs: BlobStore, out: PrintStream, input: () -> String) {
        fun loadBlob(path: String): Atom.Blob = Files.newInputStream(Path.of(path)).use { blobs.put(it) }
        fun edit(block: TypedEdit.(ValueSyntax) -> Unit): SignedBatch =
            replica.commit { id, snapshot -> TypedEdit(id, snapshot).apply { block(ValueSyntax(snapshot, ::loadBlob)) }.operations }
        fun arguments(count: IntRange, usage: String) = require(rest.size in count) { "Usage: $command $usage" }
        fun committed(batch: SignedBatch) = out.println("committed=${batch.batch.id}")
        when (command) {
            "define" -> {
                arguments(1..1, "<declaration file | ->")
                val text = if (rest[0] == "-") input() else Files.readString(Path.of(rest[0]))
                val published = mutableListOf<Pair<String, String>>()
                val batch = replica.commit { id, snapshot ->
                    published.clear()
                    TypeSyntax.parseDeclarations(text, TypeCatalog(snapshot), id).mapIndexed { index, declaration ->
                        published.add(declaration.name to id(index))
                        TypeEncoding.publish(id(index), declaration.definition, declaration.name)
                    }
                }
                published.forEach { (name, id) -> out.println("$name=${ObjectHandles.short(id)}") }
                committed(batch)
            }
            "types" -> {
                arguments(0..0, "")
                val catalog = TypeCatalog(replica.snapshot())
                catalog.entries.forEach { entry ->
                    out.println("${ObjectHandles.short(entry.id)}\t${TypeSyntax.render(TypeDeclaration(catalog.label(entry.id), entry.definition), catalog::label)}")
                }
            }
            "create" -> {
                arguments(2..2, "<type> <value>")
                var created = ""
                val batch = edit { syntax ->
                    val type = TypeSyntax.parseType(rest[0], syntax.catalog)
                    created = create(type, syntax.parse(rest[1], type))
                }
                out.println("object=${ObjectHandles.short(created)}")
                committed(batch)
            }
            "show" -> {
                arguments(0..1, "[path]")
                val syntax = ValueSyntax(replica.snapshot())
                val ids = if (rest.isEmpty()) syntax.roots() else listOf(syntax.locate(rest[0]))
                ids.forEach { out.println("${ObjectHandles.short(it)}\t${syntax.label(syntax.typeOf(it))}\t${syntax.render(it)}") }
            }
            "set" -> {
                arguments(2..2, "<path.field> <value>")
                committed(edit { syntax ->
                    val (target, field) = syntax.field(rest[0])
                    assign(target, field, syntax.parse(rest[1], syntax.fieldType(target, field)))
                })
            }
            "put" -> {
                arguments(3..3, "<map path> <key> <value>")
                committed(edit { syntax ->
                    val map = syntax.locate(rest[0])
                    val shape = syntax.structureOf(map) as? Type.MapOf ?: throw IllegalArgumentException("${rest[0]} is not a map")
                    put(map, syntax.parseKey(rest[1], shape.key), syntax.parse(rest[2], shape.value))
                })
            }
            "insert" -> {
                arguments(2..3, "<list path> <value> [first | <item path>]")
                var created = ""
                val batch = edit { syntax ->
                    val list = syntax.locate(rest[0])
                    val shape = syntax.structureOf(list) as? Type.Sequence ?: throw IllegalArgumentException("${rest[0]} is not a list")
                    val after = when (val position = rest.getOrNull(2)) {
                        null -> syntax.items(list).lastOrNull()
                        "first" -> null
                        else -> syntax.locate(position)
                    }
                    created = insert(list, after, syntax.parse(rest[1], shape.element))
                }
                out.println("object=${ObjectHandles.short(created)}")
                committed(batch)
            }
            "switch" -> {
                arguments(2..2, "<sum path> <variant>")
                committed(edit { syntax ->
                    val sum = syntax.locate(rest[0])
                    require(syntax.structureOf(sum) is Type.Sum) { "${rest[0]} is not a sum" }
                    switch(sum, syntax.parse(rest[1], syntax.typeOf(sum)) as Value.Variant)
                })
            }
            "delete" -> {
                arguments(1..1, "<path>")
                committed(edit { syntax -> delete(syntax.locate(rest[0])) })
            }
            "move" -> {
                arguments(2..3, "<item path> <list path | root> [first | <item path>]")
                committed(edit { syntax ->
                    val item = syntax.locate(rest[0])
                    val destination = if (rest[1] == "root") null else syntax.locate(rest[1])
                    val after = when (val position = rest.getOrNull(2)) {
                        null -> destination?.let { list -> syntax.items(list).lastOrNull { it != item } }
                        "first" -> null
                        else -> syntax.locate(position)
                    }
                    move(item, destination, after)
                })
            }
            else -> throw IllegalArgumentException("Unknown typed command: $command")
        }
    }
}
