package ai.yaay.crdt

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import kotlin.test.*

class CommitFlowTest {
    private val validator = MutationValidator { _, _, _, _ -> }
    private fun open(path: java.nio.file.Path) = DurableReplica.open(path, "flow", validator = validator)
    private fun create(replica: DurableReplica, value: String) = replica.commit { id, _ ->
        listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, value))
    }
    @Test fun slowCollectorsReplayEveryCompleteCommitAndResumeAfterRestart() = runBlocking {
        val root = Files.createTempDirectory("yaay-flow-")
        var saved = Frontier()
        open(root).use { replica ->
            create(replica, "before subscription")
            val collected = async {
                replica.commits().onEach { delay(5) }.take(15).toList()
            }
            repeat(14) { create(replica, "item $it") }
            val commits = withTimeout(5_000) { collected.await() }
            assertEquals((1L..15L).toList(), commits.map { it.token.id.counter })
            commits.forEachIndexed { index, commit ->
                assertEquals(index + 1, commit.snapshot.objects.size)
                assertTrue(commit.snapshot.objects.values.all { it.text.isNotEmpty() })
                assertTrue(commit.snapshot.frontier.contains(commit.token.id))
            }
            saved = commits[9].snapshot.frontier
        }
        open(root).use { replica ->
            val resumed = withTimeout(5_000) { replica.commits(saved).take(5).toList() }
            assertEquals((11L..15L).toList(), resumed.map { it.token.id.counter })
        }
    }
    @Test fun statesAreAtomicFailedWritesDoNotEmitAndCloseDrainsCommitFlow() = runBlocking {
        val replica = open(Files.createTempDirectory("yaay-flow-close-"))
        val all = async { replica.commits().toList() }
        val state = replica.states().first()
        assertTrue(state.snapshot.objects.isEmpty())
        create(replica, "complete")
        assertFails { replica.commit { _, _ -> listOf(Operation.Delete("missing")) } }
        val next = replica.states().first()
        assertEquals("complete", next.snapshot.objects.values.single().text)
        replica.close()
        val drained = withTimeout(5_000) { all.await() }
        assertEquals(1, drained.size)
    }
    @Test fun remoteBufferedDrainAndDuplicatesHaveExactlyOneEmissionPerAcceptedBatch() = runBlocking {
        open(Files.createTempDirectory("yaay-flow-a-")).use { source ->
            DurableReplica.open(Files.createTempDirectory("yaay-flow-b-"), "flow", source.founder, validator = validator).use { target ->
                val a = create(source, "a")
                val b = create(source, "b")
                assertEquals(IngestResult.BUFFERED, target.ingest(b, source.author))
                assertEquals(IngestResult.APPLIED, target.ingest(a, source.author))
                assertEquals(IngestResult.DUPLICATE, target.ingest(a, source.author))
                val all = async { target.commits().toList() }
                target.close()
                val changes = withTimeout(5_000) { all.await() }
                assertEquals(listOf(a, b), changes.map { it.signed })
                assertEquals(listOf(1, 2), changes.map { it.snapshot.objects.size })
            }
        }
    }
    @Test fun unsupportedFramesFreezeStateWithoutEmittingAnAppliedCommit(): Unit = runBlocking {
        val crypto = JvmBatchCrypto(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
        DurableReplica.open(Files.createTempDirectory("yaay-flow-unknown-"), "flow", crypto.author, validator = validator).use { target ->
            val first = BatchId(crypto.author, 1)
            val known = crypto.sign(Batch("flow", 1, first, Frontier(mapOf(crypto.author to 1)),
                listOf(Operation.Create(OpId(first, 0).stableId(), "text", Shape.TEXT))))
            target.ingest(known, crypto.author)
            val unsupported = crypto.sign(Batch("flow", 2, BatchId(crypto.author, 2), Frontier(mapOf(crypto.author to 2)),
                listOf(Operation.Unknown(50, listOf(1)))))
            target.ingest(unsupported, crypto.author)
            assertTrue(target.states().first().readOnly)
            val result = async { target.commits().toList() }
            target.close()
            assertEquals(listOf(known), withTimeout(5_000) { result.await() }.map { it.signed })
        }
    }

}
