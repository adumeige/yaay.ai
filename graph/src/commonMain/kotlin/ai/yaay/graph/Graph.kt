package ai.yaay.graph

import ai.yaay.crdt.*
import kotlinx.coroutines.flow.Flow

/** Application queries; database syntax and database handles stay inside the adapter. */
public sealed interface GraphQuery {
    public data class ObjectById(public val id: String) : GraphQuery
    public data class Objects(public val type: String? = null, public val includeDeleted: Boolean = false) : GraphQuery
    public data object Roots : GraphQuery
    public data class Children(public val parent: String) : GraphQuery
    public data class Referrers(public val target: String) : GraphQuery
    public data class TextSearch(public val text: String) : GraphQuery
}

public data class GraphCheckpoint(public val workspace: String, public val frontier: Frontier) {
    public fun includes(token: CommitToken): Boolean = token.workspace == workspace && frontier.contains(token.id)
}

/** Absence is only absence from this checkpoint, never authoritative proof of deletion. */
public data class GraphResult(public val checkpoint: GraphCheckpoint, public val objects: List<ResolvedObject>)

public data class ProjectionStatus(
    public val checkpoint: GraphCheckpoint?,
    public val failure: String? = null,
    public val closed: Boolean = false,
)

public interface GraphReadModel {
    public val status: Flow<ProjectionStatus>
    public suspend fun read(query: GraphQuery, after: CommitToken? = null): GraphResult
    /** Initial complete result followed by complete results; intermediate checkpoints may coalesce. */
    public fun watch(query: GraphQuery): Flow<GraphResult>
    public suspend fun await(token: CommitToken): GraphCheckpoint
    public suspend fun rebuild()
}

/** Disposable local storage SPI, owned by the projector, never an application write API. */
public interface GraphStore : AutoCloseable {
    public val workspace: String
    public val founder: String
    public fun checkpoint(): GraphCheckpoint?
    /** Install a complete resolved state and its checkpoint in one database transaction. */
    public fun replace(snapshot: Snapshot)
    /** Reconstruct a fresh cache; keep the prior complete generation if staging fails. */
    public fun rebuild(snapshot: Snapshot)
    public fun query(query: GraphQuery): GraphResult
    override fun close()
}
