// Home for bootstrap type definitions shared by all applications.
package ai.yaay.documents.types.builtin

import ai.yaay.documents.types.Type
import ai.yaay.documents.types.TypeDefinition

/**
 * Bootstrap definitions known to every peer without being published. Their identities cannot collide
 * with published objects, whose IDs always start with a verified author key.
 *
 * The workspace tree is ordinary typed data: a folder's children and the top level hold `Node`s; a
 * `Document` is a shell whose content is an embedded value of any type.
 */
public object BuiltInTypes {
    public const val NODE: String = "yaay:builtin:node:1"
    public const val FOLDER: String = "yaay:builtin:folder:1"
    public const val DOCUMENT: String = "yaay:builtin:document:1"
    public val TREE: Set<String> = setOf(NODE, FOLDER, DOCUMENT)

    public val node: Type.Named = Type.Named(NODE)
    public val folder: Type.Named = Type.Named(FOLDER)
    public val document: Type.Named = Type.Named(DOCUMENT)

    public data class BuiltIn(public val id: String, public val name: String, public val definition: TypeDefinition)
    public val all: List<BuiltIn> = listOf(
        BuiltIn(FOLDER, "Folder", TypeDefinition(emptyList(), false, Type.Record(mapOf("title" to Type.Scalar.STRING, "children" to Type.Sequence(node))))),
        BuiltIn(DOCUMENT, "Document", TypeDefinition(emptyList(), false, Type.Record(mapOf("title" to Type.Scalar.STRING, "content" to Type.Any)))),
        BuiltIn(NODE, "Node", TypeDefinition(emptyList(), false, Type.Sum(mapOf("Folder" to folder, "Document" to document)))),
    )
    private val byId = all.associateBy { it.id }
    public fun definition(id: String): TypeDefinition? = byId[id]?.definition
}
