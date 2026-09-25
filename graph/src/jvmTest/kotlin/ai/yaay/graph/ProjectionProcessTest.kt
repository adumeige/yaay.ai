package ai.yaay.graph

import ai.yaay.crdt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

object ProjectionCrashProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        DurableReplica.open(root.resolve("replica"), "crash", validator = MutationValidator { _, _, _, _ -> }).use { replica ->
            EmbeddedYouTrackGraph(root.resolve("graph"), replica.workspace, replica.founder) {
                if (it.name == args[1]) Runtime.getRuntime().halt(73)
            }.use { if (args[1].endsWith("INSTALL")) it.rebuild(replica.snapshot()) else it.replace(replica.snapshot()) }
        }
    }
}

class ProjectionProcessTest {
    @Test fun killedProjectionRecoversAtomicCheckpointAndReplaysJournal(): Unit = runBlocking {
        for (boundary in listOf(ProjectionBoundary.BEFORE_COMMIT, ProjectionBoundary.AFTER_COMMIT, ProjectionBoundary.BEFORE_INSTALL, ProjectionBoundary.AFTER_INSTALL)) {
            val root = Files.createTempDirectory("yaay-project-crash-")
            val validator = MutationValidator { _, _, _, _ -> }
            fun open() = DurableReplica.open(root.resolve("replica"), "crash", validator = validator)
            open().use { replica ->
                replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "old")) }
                EmbeddedYouTrackGraph(root.resolve("graph"), replica.workspace, replica.founder).use { it.replace(replica.snapshot()) }
                replica.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "new")) }
            }
            val log = root.resolve("child.log")
            val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("yaay.test.classpath"), ProjectionCrashProbe::class.java.name,
                root.toString(), boundary.name).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Child stalled: $log")
                assertEquals(73, child.exitValue(), Files.readString(log))
            } finally { child.destroyForcibly() }
            open().use { replica ->
                val store = EmbeddedYouTrackGraph(root.resolve("graph"), replica.workspace, replica.founder)
                val previous = store.query(GraphQuery.Objects())
                // The disposable graph uses YouTrackDB's batched WAL flush. A process halt
                // after transaction commit may recover either complete checkpoint.
                if (boundary in listOf(ProjectionBoundary.BEFORE_COMMIT, ProjectionBoundary.BEFORE_INSTALL)) assertEquals(1, previous.objects.size)
                else if (boundary == ProjectionBoundary.AFTER_INSTALL) assertEquals(2, previous.objects.size)
                else assertTrue(previous.objects.size in 1..2)
                assertEquals(previous.objects.size.toLong(), previous.checkpoint.frontier[replica.author])
                val projection = GraphProjection(replica, store, this)
                try {
                    try { withTimeout(10_000) { projection.await(replica.history().last().commitToken()) } }
                    catch (failure: Exception) { error("$boundary: ${projection.status.first()}: $failure") }
                    assertEquals(listOf("new", "old"), projection.read(GraphQuery.Objects()).objects.map { it.text }.sorted())
                } finally { projection.close() }
            }
        }
    }
}
