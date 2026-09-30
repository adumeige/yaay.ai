package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import ai.yaay.documents.types.builtin.BuiltInTypes

/**
 * The workspace folder/document tree read from one snapshot. It is ordinary typed data: live `Node`s with
 * no parent form the top level, and a folder's `children` list holds its nodes. A node is visible only
 * when it, its payload and every ancestor are live, so deleting a folder hides its subtree.
 */
public class WorkspaceTree(private val snapshot: Snapshot) {
    public enum class Kind { FOLDER, DOCUMENT }
    public data class Entry(
        public val node: String,
        public val kind: Kind,
        public val title: String,
        /** The Folder or Document record inside the node. */
        public val payload: String,
        /** A folder's children list, or a document's content instance. */
        public val inner: String?,
        public val children: List<Entry>,
    )

    private val nodeType = TypeEncoding.encode(BuiltInTypes.node)

    public fun isNode(id: String): Boolean = snapshot.objects[id]?.type == nodeType

    public fun topLevel(): List<Entry> = snapshot.objects.values
        .filter { it.type == nodeType && !it.deleted && it.parent == null }
        .mapNotNull { entry(it.id, emptySet()) }
        .sortedWith(compareBy({ it.title }, { it.node }))

    /** A visible node's entry with its visible subtree, or null when it or an ancestor is deleted. */
    public fun entry(node: String): Entry? {
        var current: String? = snapshot.objects[node]?.parent
        while (current != null) {
            val obj = snapshot.objects[current] ?: return null
            if (obj.deleted) return null
            current = obj.parent
        }
        return entry(node, emptySet())
    }

    /** The list that holds a folder's nodes; [folder] may be the folder's node or its Folder record. */
    public fun childrenList(folder: String): String {
        val record = if (isNode(folder)) payload(folder) else folder
        require(snapshot.objects[record]?.type == TypeEncoding.encode(BuiltInTypes.folder)) { "Not a folder" }
        return (snapshot[record].fields["children"] as Atom.Instance).id
    }

    public fun payload(node: String): String {
        require(isNode(node)) { "Not a node" }
        return (snapshot[node].fields["value"] as Atom.Variant).payload ?: throw IllegalArgumentException("Node has no payload")
    }

    private fun entry(node: String, path: Set<String>): Entry? {
        val obj = snapshot.objects[node] ?: return null
        if (obj.deleted || node in path) return null
        val variant = obj.fields["value"] as? Atom.Variant ?: return null
        val payload = snapshot.objects[variant.payload ?: return null] ?: return null
        if (payload.deleted) return null
        val title = (payload.fields["title"] as? Atom.Str)?.value ?: ""
        val inner = (payload.fields[if (variant.tag == "Folder") "children" else "content"] as? Atom.Instance)?.id
        return if (variant.tag == "Folder") {
            val list = inner?.let { snapshot.objects[it] }?.takeIf { !it.deleted }
            val children = list?.children.orEmpty().mapNotNull { entry(it, path + node) }
            Entry(node, Kind.FOLDER, title, payload.id, inner, children)
        } else Entry(node, Kind.DOCUMENT, title, payload.id, inner, emptyList())
    }
}

private fun nodeValue(tag: String, fields: Map<String, Value>): Value = Value.Variant(tag, Value.Record(fields))
private fun titleValue(value: String): Value = Value.Atomic(Atom.Str(value))

/** Stages a new folder at the top level (`list == null`) or in a folder's children list; returns its node. */
public fun TypedEdit.newFolder(title: String, list: String? = null, after: String? = null): String {
    val value = nodeValue("Folder", mapOf("title" to titleValue(title), "children" to Value.Sequence(emptyList())))
    return if (list == null) create(BuiltInTypes.node, value) else insert(list, after, value)
}

/** Stages a new document whose content is [content], an embedded value of any concrete type. */
public fun TypedEdit.newDocument(title: String, content: Value.Typed, list: String? = null, after: String? = null): String {
    val value = nodeValue("Document", mapOf("title" to titleValue(title), "content" to content))
    return if (list == null) create(BuiltInTypes.node, value) else insert(list, after, value)
}

/** Renames a folder or document; [payload] is its Folder or Document record. */
public fun TypedEdit.rename(payload: String, title: String) { assign(payload, "title", titleValue(title)) }
