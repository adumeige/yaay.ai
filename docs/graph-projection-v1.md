# Embedded graph projection and reactive services (M3)

V1 runs YouTrackDB **embedded in the JVM process**, with a disk cache under the
replica's local `graph/` directory. No database server is required. The CRDT journal
remains authoritative. Graph writes occur only through the projector; content
services and future workflow services commit through `DurableReplica`.

The `graph` module provides an application SPI in common Kotlin and the embedded
adapter/projector on JVM. `documents.typedGraphStore` adds reference discovery inside
algebraic map keys. Engine query syntax, vertices and transaction handles remain in
the adapter. Application queries cover identity, type, roots, ordered children,
referrers and basic case-insensitive text matching. Objects retain tombstones,
embedded identity, text positions and blob descriptors. Queries return detached
values and their complete projection checkpoint; a missing result is not proof of
an authoritative deletion.

## Kotlin Flow contract

- `replica.states()` emits an initial complete authoritative snapshot and subsequent
  complete snapshots, with the read-only compatibility flag. Slow collectors may
  skip intermediate states.
- `replica.commits(after = frontier)` replays accepted batches after a saved causal
  cursor, then follows new durable batches. Each emission includes the signed batch,
  its `CommitToken`, and the resolved snapshot immediately after that whole batch.
  Every collector has its own cursor. Notifications may coalesce; retained journal
  history supplies every accepted batch without dropping events or blocking writes.
- Local commits, bootstrap and remote ingestion feed the same streams, including
  batches released from the dependency buffer. Invalid, duplicate, pending and
  unsupported batches are never emitted as new applied commits. Unsupported frames
  still freeze the workspace and update `states()`.
- `projection.watch(query)` emits an initial complete result and refreshed complete
  results. It may coalesce intermediate checkpoints. `projection.status` reports
  checkpoints, retry failures and closure; consumers can compose either Flow with
  ordinary `map`, `combine`, `stateIn` and lifecycle cancellation.
- `projection.read(query, after = token)` and `projection.await(token)` suspend until
  the local graph includes the commit. Use `withTimeout` for a caller deadline.
  Cancellation cancels the wait/collector, not a committed write. Tokens are local
  freshness barriers, not global replication acknowledgements.

The batch Flow is a replay stream, not an exactly-once side-effect mechanism. A
service restarting from an older cursor replays batches; persist its own progress
and use idempotent effects. Future workflow eligibility must still be checked and
committed against authoritative run state. Graph notifications do not authorize
execution.

A complete service example (inside a suspending function, with an owned scope):

```kotlin
val projection = withContext(Dispatchers.IO) {
    GraphProjection(
        replica, typedGraphStore(replica.directory.resolve("graph"), replica), serviceScope,
    )
}
val updates = serviceScope.launch {
    projection.watch(GraphQuery.Roots).collect { result -> render(result) }
}
try {
    val batch = withContext(Dispatchers.IO) {
        replica.commit { id, before ->
            TypedEdit(id, before).apply {
                create(Type.Scalar.TEXT, Value.Text("Hello"))
            }.operations
        }
    }
    val visible = withTimeout(5_000) {
        projection.read(GraphQuery.Objects(), after = batch.commitToken())
    }
    // visible.checkpoint includes this write; other peers may still be offline.
} finally {
    updates.cancelAndJoin()
    projection.close()
    // Close the replica only after its downstream services are closed.
}
```

`DurableReplica.commit` remains synchronous for existing CLI/transport callers;
perform blocking commits on `Dispatchers.IO` in suspending application services.
Replica Flow reads also run on IO. A single serialized writer owns the replica and
a separate serialized projector owns the local graph. No application callbacks run
inside the graph transaction. Streams terminate when their source closes; a source
replica's batch stream drains accepted history before completing. Scope owners must
close the projector, including when cancelling their service scope.

## Transactions, recovery and rebuild

Every accepted batch installs its resolved graph state and causal frontier in one
YouTrackDB transaction. V1 replaces the complete graph snapshot per batch. Identity
and type are indexed; containment and reference relations are actual graph edges.
The adapter reads back the checkpoint before acknowledging a projection, and query
results and their checkpoint are read in the same database transaction.

A failed transaction leaves the previous complete projection available where the
database remains readable. The projector reports the failure and retries from the
stored checkpoint. After three consecutive failures it builds a fresh generation
from current authoritative state. Explicit `rebuild()` uses the same mechanism:
build and close a staged database, reopen and verify its checkpoint, then atomically
install a forced `CURRENT` pointer. Readers cannot observe a partly built generation.
A crash before installation retains the old generation; a crash afterwards selects
the new complete generation. Prior/failed generations are retained for diagnosis;
with the replica stopped, the entire disposable `graph/` directory can be removed
and reconstructed. Do not remove the replica journal or identity.

Graph storage is bound to both workspace and founder and has an exclusive OS owner
lock. An ahead-of-journal checkpoint (for example after restoring an older journal)
is replaced from authoritative state. Graph rebuilds do not append CRDT operations,
change content identity, or issue workflow commands.

## Dependency and tested limits

The adapter pins the timestamped publication
`io.youtrackdb:youtrackdb-embedded:0.5.0-20260924.231536-66569b2-dev-20260924.232310-1`
from the narrowly scoped Sonatype snapshot repository. This is a development build,
not a stable release. The shaded jar is used without transitive scripting runtimes;
SLF4J API/JDK logging are explicit dependencies. No script execution is exposed.
Kotlin Flow uses `kotlinx-coroutines` 1.11.0. Upstream references:
[YouTrackDB embedded setup](https://github.com/JetBrains/youtrackdb/blob/66569b2477828bf8110ac5e7f3286ea9def1ba93/README.md)
and [StateFlow semantics](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/).

The crash tests found that this YouTrackDB build can recover an older checkpoint
with a storage allocation error that prevents further writes. Its transaction helper
can log a commit failure without propagating it to the caller. Checkpoint verification
and staged rebuilding cover this observed failure. Ordinary graph transactions use
the database's batched WAL flush: an abrupt process halt can lose the latest cache
transaction, which is then replayed from the durable CRDT journal.

Tests run on Linux/JDK 21 and macOS/JDK 25 against real embedded disk databases and child JVMs. They
cover atomic rollback, lag/retry, token waiting, complete query snapshots, typed
references/tombstones/order, restart, explicit rebuild, automatic recovery, and
process halts before/after both transaction commit and generation installation.
They do not simulate storage-device power loss. Full-snapshot projection and linear
text matching prioritize correctness in this V1; large-workspace performance and
incremental indexing remain future work. Workflow execution is still a later milestone.
The previously deferred two-machine SMB/NFS manual gate is unchanged.
