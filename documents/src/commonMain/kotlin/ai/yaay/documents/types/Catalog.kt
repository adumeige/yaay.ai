package ai.yaay.documents.types

import ai.yaay.crdt.*

/**
 * Short object handles for people: the last characters of the author key plus the batch counter and
 * operation index. Every Ed25519 author key shares the same leading DER bytes, so the key's tail is used.
 * Any longer author suffix, or the full identity, resolves too.
 */
public object ObjectHandles {
    private const val AUTHOR_SUFFIX = 6
    private fun split(id: String): Triple<String, String, String>? {
        val parts = id.split(':')
        if (parts.size < 3) return null
        return Triple(parts.dropLast(2).joinToString(":"), parts[parts.size - 2], parts.last())
    }
    public fun short(id: String): String = split(id)?.let { (author, counter, index) -> "${author.takeLast(AUTHOR_SUFFIX)}:$counter:$index" } ?: id
    public fun resolve(ids: Collection<String>, handle: String): String {
        if (handle in ids) return handle
        val (suffix, counter, index) = split(handle) ?: throw IllegalArgumentException("Malformed handle $handle; expected <author suffix>:<counter>:<index>")
        val matches = ids.filter { id -> split(id)?.let { (author, c, i) -> c == counter && i == index && author.endsWith(suffix) } == true }
        require(matches.isNotEmpty()) { "Unknown object $handle" }
        require(matches.size == 1) { "Ambiguous handle $handle; use a longer author suffix" }
        return matches.single()
    }
}

/**
 * A derived, read-only view of the published type definitions in one snapshot. Names are display
 * metadata carried by each immutable definition; identity is always the definition's object ID, and
 * several versions may share a name. This is not a separate registry.
 */
public class TypeCatalog(snapshot: Snapshot) {
    public data class Entry(public val id: String, public val name: String?, public val definition: TypeDefinition)

    public val entries: List<Entry> = snapshot.objects.values
        .filter { it.type == TypeEncoding.DEFINITION_TYPE && !it.deleted }
        .map { Entry(it.id, (it.fields["name"] as? Atom.Str)?.value, TypeEncoding.definition((it.fields.getValue("value") as Atom.Str).value)) }
        .sortedWith(compareBy({ it.name ?: "" }, { it.id }))
    private val byId = entries.associateBy { it.id }
    private val byName = entries.filter { it.name != null }.groupBy { it.name!! }

    public fun entry(id: String): Entry? = byId[id]
    public fun resolve(name: String): Entry {
        val versions = byName[name] ?: throw IllegalArgumentException("Unknown type $name")
        require(versions.size == 1) {
            "Type name $name has ${versions.size} versions (${versions.joinToString { "@" + ObjectHandles.short(it.id) }}); write $name@<handle>"
        }
        return versions.single()
    }
    public fun resolve(name: String?, handle: String): Entry {
        val entry = byId.getValue(ObjectHandles.resolve(byId.keys, handle))
        require(name == null || entry.name == name) { "@$handle is ${entry.name ?: "an unnamed type"}, not $name" }
        return entry
    }
    /** The shortest unambiguous way to write this definition in the syntax. */
    public fun label(id: String): String {
        val name = byId[id]?.name ?: return "@" + ObjectHandles.short(id)
        return if (byName.getValue(name).size == 1) name else "$name@${ObjectHandles.short(id)}"
    }
}
