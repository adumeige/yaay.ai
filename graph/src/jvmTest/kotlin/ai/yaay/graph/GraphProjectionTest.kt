package ai.yaay.graph

import ai.yaay.crdt.*
import ai.yaay.documents.*
import ai.yaay.documents.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class GraphProjectionTest {
    private fun replica(path: java.nio.file.Path) = DurableReplica.open(path, "graph-test", validator = TypedMutationValidator())
    private fun text(replica: DurableReplica, value: String): SignedBatch = replica.commit { id, before ->
        TypedEdit(id, before).apply { create(Type.Scalar.TEXT, Value.Text(value)) }.operations
    }
    @Test fun embeddedAtomicProjectionRollbackRestartAndRebuild(): Unit = runBlocking {
        val root = Files.createTempDirectory("yaay-graph-")
        replica(root.resolve("replica")).use { replica ->
            val first = text(replica, "first")
            val fail = AtomicBoolean(false)
            val graphPath = root.resolve("graph")
            EmbeddedYouTrackGraph(graphPath, replica.workspace, replica.founder) { boundary ->
                if (boundary == ProjectionBoundary.BEFORE_COMMIT && fail.getAndSet(false)) error("injected rollback")
            }.use { store ->
                store.replace(replica.snapshot())
                val before = store.query(GraphQuery.Objects())
                text(replica, "second")
                fail.set(true)
                assertFails { store.replace(replica.snapshot()) }
                assertEquals(before, store.query(GraphQuery.Objects()))
            }
            val projection = GraphProjection(replica, EmbeddedYouTrackGraph(graphPath, replica.workspace, replica.founder), this)
            try {
                withTimeout(20_000) { projection.await(replica.history().last().commitToken()) }
                val result = projection.read(GraphQuery.Objects(), first.commitToken())
                assertEquals(setOf("first", "second"), result.objects.map { it.text }.toSet())
                val history = replica.history()
                projection.rebuild()
                assertEquals(result, projection.read(GraphQuery.Objects()))
                assertEquals(history, replica.history())
            } finally { projection.close() }
        }
    }
    @Test fun failedProjectionRetriesWithoutBlockingCommitsAndWatchEmitsCompleteSnapshots(): Unit = runBlocking {
        val root = Files.createTempDirectory("yaay-graph-reactive-")
        replica(root.resolve("replica")).use { replica ->
            val reject = AtomicBoolean(false)
            val store = EmbeddedYouTrackGraph(root.resolve("graph"), replica.workspace, replica.founder) { boundary ->
                if (boundary == ProjectionBoundary.BEFORE_COMMIT && reject.get()) error("temporarily unavailable")
            }
            val projection = GraphProjection(replica, store, this, retryDelayMillis = 20)
            try {
                val initial = withTimeout(10_000) { projection.watch(GraphQuery.Objects()).first() }
                assertTrue(initial.objects.isEmpty())
                reject.set(true)
                val a = text(replica, "durable during graph failure")
                withTimeout(5_000) { projection.status.first { it.failure != null } }
                assertTrue(projection.read(GraphQuery.Objects()).objects.isEmpty())
                val waiting = async { projection.read(GraphQuery.Objects(), a.commitToken()) }
                yield()
                assertFalse(waiting.isCompleted)
                val b = text(replica, "also durable")
                val observed = async {
                    projection.watch(GraphQuery.Objects()).first { it.checkpoint.includes(b.commitToken()) }
                }
                reject.set(false)
                val result = withTimeout(10_000) { observed.await() }
                assertEquals(2, result.objects.size)
                assertTrue(result.objects.all { it.text.isNotEmpty() })
                assertTrue(withTimeout(5_000) { waiting.await() }.checkpoint.includes(a.commitToken()))
                assertFailsWith<IllegalArgumentException> { projection.await(CommitToken("other", a.batch.id)) }
            } finally { projection.close() }
        }
    }

    @Test fun graphQueriesKeepOrderTombstonesAndReferencesIncludingMapKeys(): Unit = runBlocking {
        val root = Files.createTempDirectory("yaay-graph-query-")
        replica(root.resolve("replica")).use { replica ->
            var list = ""; var target = ""; var ref = ""; var map = ""
            replica.commit { id, before -> TypedEdit(id, before).apply {
                list = create(Type.Sequence(Type.Scalar.TEXT), Value.Sequence(listOf(Value.Text("one"), Value.Text("two"))))
                target = create(Type.Scalar.STRING, Value.Atomic(Atom.Str("target")))
            }.operations }
            replica.commit { id, before -> TypedEdit(id, before).apply {
                ref = create(Type.Ref(Type.Scalar.STRING), Value.Atomic(Atom.Ref(target)))
                map = create(Type.MapOf(Type.Scalar.STRING, Type.Ref(Type.Scalar.STRING)),
                    Value.KeyedEntries(mapOf(KeyValue.Atomic(Atom.Ref(target)) to Value.Atomic(Atom.Str("keyed")))))
            }.operations }
            val projection = GraphProjection(replica, typedGraphStore(root.resolve("graph"), replica), this)
            try {
                withTimeout(10_000) { projection.await(replica.history().last().commitToken()) }
                assertEquals(listOf("one", "two"), projection.read(GraphQuery.Children(list)).objects.map { it.text })
                assertEquals(setOf(ref, map), projection.read(GraphQuery.Referrers(target)).objects.map { it.id }.toSet())
                assertEquals(1, projection.read(GraphQuery.TextSearch("ONE")).objects.size)
                val deleted = replica.commit { _, _ -> listOf(Operation.Delete(target)) }
                withTimeout(10_000) { projection.await(deleted.commitToken()) }
                assertTrue(projection.read(GraphQuery.ObjectById(target)).objects.single().deleted)
                assertEquals(setOf(ref, map), projection.read(GraphQuery.Referrers(target)).objects.map { it.id }.toSet())
                assertFalse(projection.read(GraphQuery.Objects()).objects.any { it.id == target })
                assertEquals(replica.snapshot().objects.values.sortedBy { it.id }, projection.read(GraphQuery.Objects(includeDeleted = true)).objects)
            } finally { projection.close() }
        }
    }

    @Test fun failureAfterDatabaseCommitIsIdempotentAndCancellationEndsSubscriptions(): Unit = runBlocking {
        val root = Files.createTempDirectory("yaay-graph-after-")
        replica(root.resolve("replica")).use { replica ->
            val committed = text(replica, "once")
            val fail = AtomicBoolean(true)
            val projection = GraphProjection(replica, EmbeddedYouTrackGraph(root.resolve("graph"), replica.workspace, replica.founder) {
                if (it == ProjectionBoundary.AFTER_COMMIT && fail.getAndSet(false)) error("notification lost")
            }, this, retryDelayMillis = 20)
            val stream = async { projection.watch(GraphQuery.Objects()).toList() }
            withTimeout(10_000) { projection.await(committed.commitToken()) }
            assertEquals(1, projection.read(GraphQuery.Objects()).objects.size)
            projection.close()
            withTimeout(5_000) { stream.await() }
            assertTrue(projection.status.first().closed)
            assertFails { projection.read(GraphQuery.Objects()) }
        }
    }

}
