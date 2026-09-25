package ai.yaay.graph

import ai.yaay.crdt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One asynchronous projector per local graph store. Its lifetime is a child of [scope].
 * Close this service before its replica; [close] joins the worker and closes the store.
 * No projection callback is invoked in the replica's durable writer transaction.
 */
public class GraphProjection(
    private val replica: DurableReplica,
    private val store: GraphStore,
    scope: CoroutineScope,
    private val retryDelayMillis: Long = 250,
) : GraphReadModel {
    private val gate = Mutex()
    private val mutableStatus = MutableStateFlow(ProjectionStatus(null))
    override val status: Flow<ProjectionStatus> = mutableStatus.map { state ->
        state.copy(checkpoint = state.checkpoint?.let { it.copy(frontier = Frontier(it.frontier.counters.toMap())) })
    }
    private var closed = false
    private val worker: Job
    init {
        require(replica.workspace == store.workspace && replica.founder == store.founder) { "Graph identity mismatch" }
        require(retryDelayMillis > 0)
        worker = scope.launch(Dispatchers.IO) {
            var failures = 0
            try {
                while (isActive) {
                    try {
                        val checkpoint = gate.withLock {
                            if (failures >= 3) {
                                store.rebuild(replica.snapshot())
                                failures = 0
                            }
                            val current = store.checkpoint()
                            val authoritative = replica.snapshot()
                            // A restored/replaced journal can leave a cache ahead of authoritative state.
                            if (current == null) store.replace(Resolver.resolve(emptyList()))
                            else if (current.frontier.counters.any { (author, count) -> count > authoritative.frontier[author] }) {
                                store.replace(authoritative)
                            }
                            store.checkpoint()!!.also { publish(it) }
                        }
                        replica.commits(checkpoint.frontier).collect { commit ->
                            gate.withLock {
                                if (!store.checkpoint()!!.includes(commit.token)) {
                                    store.replace(commit.snapshot)
                                    val applied = store.checkpoint()!!
                                    check(applied.includes(commit.token)) { "Projection did not include committed batch" }
                                    publish(applied)
                                    failures = 0
                                }
                            }
                        }
                        break // Replica closed; its final durable commits have been drained.
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        failures++
                        mutableStatus.value = mutableStatus.value.copy(failure = failure.message ?: failure.javaClass.simpleName)
                        delay(retryDelayMillis)
                    }
                }
            } finally {
                mutableStatus.value = mutableStatus.value.copy(closed = true)
            }
        }
    }
    private fun publish(checkpoint: GraphCheckpoint) { mutableStatus.value = ProjectionStatus(checkpoint) }
    override suspend fun await(token: CommitToken): GraphCheckpoint {
        require(token.workspace == replica.workspace && token.id.counter > 0) { "Invalid or cross-workspace commit token" }
        val state = status.first { it.checkpoint?.includes(token) == true || it.closed }
        return state.checkpoint?.takeIf { it.includes(token) } ?: error("Projector closed before token was projected")
    }
    override suspend fun read(query: GraphQuery, after: CommitToken?): GraphResult {
        if (after != null) await(after)
        else status.first { it.checkpoint != null || it.closed }
        return withContext(Dispatchers.IO) { gate.withLock { check(!closed); store.query(query) } }
    }
    override fun watch(query: GraphQuery): Flow<GraphResult> = status.transformWhile { state ->
        if (!state.closed && state.checkpoint != null) {
            val result = withContext(Dispatchers.IO) { gate.withLock { if (closed) null else store.query(query) } }
            if (result != null) emit(result)
        }
        !state.closed
    }.distinctUntilChanged()

    override suspend fun rebuild(): Unit = withContext(Dispatchers.IO) {
        gate.withLock {
            check(!closed && worker.isActive) { "Projector closed" }
            try {
                store.rebuild(replica.snapshot())
                publish(store.checkpoint()!!)
            } catch (failure: Exception) {
                mutableStatus.value = mutableStatus.value.copy(
                    checkpoint = runCatching { store.checkpoint() }.getOrNull() ?: mutableStatus.value.checkpoint,
                    failure = failure.message ?: failure.javaClass.simpleName,
                )
                throw failure
            }
        }
    }
    public suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        worker.cancelAndJoin()
        gate.withLock {
            if (!closed) { closed = true; store.close() }
            mutableStatus.value = mutableStatus.value.copy(closed = true)
        }
    }
}
