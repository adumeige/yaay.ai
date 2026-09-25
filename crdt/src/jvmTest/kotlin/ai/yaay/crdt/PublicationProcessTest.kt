package ai.yaay.crdt

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

object PublicationProbe {
    @JvmStatic fun main(args: Array<String>) {
        val store = Path.of(args[0])
        DurableReplica.open(store, "publication", validator = MutationValidator { _, _, _, _ -> }).use { replica ->
            val endpoint = SyncEndpoint(replica, BlobStore(store.resolve("chunks")))
            DirectorySync(endpoint, Path.of(args[1])) { boundary ->
                if (boundary.name == args[2]) { println(boundary.name); System.out.flush(); Runtime.getRuntime().halt(79) }
            }.publish()
        }
    }
}

class PublicationProcessTest {
    @Test fun killedPublisherLeavesOnlyVerifiedCandidatesAndRestartRepublishesIdempotently() {
        val validator = MutationValidator { _, _, _, _ -> }
        for (boundary in PublicationBoundary.entries) {
            val root = Files.createTempDirectory("yaay-publication-crash-")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val source = root.resolve("a"); val destination = root.resolve("b"); val share = root.resolve("share")
                var founder = ""
                val expected = DurableReplica.open(source, "publication", validator = validator).use { a ->
                    founder = a.author
                    DurableReplica.open(destination, "publication", founder, validator = validator).use { b -> a.commit { _, _ -> listOf(Operation.Membership(b.author, true)) } }
                    a.commit { id, _ -> listOf(Operation.Create(id(0), "text", Shape.TEXT), Operation.EditText(id(0), null, "complete")) }
                    a.snapshot()
                }
                val classpath = System.getProperty("yaay.test.classpath")
                val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, PublicationProbe::class.java.name, source.toString(), share.toString(), boundary.name).redirectErrorStream(true).start()
                try {
                    assertEquals(boundary.name, executor.submit<String> { process.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS))
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(79, process.exitValue())
                } finally { process.destroyForcibly() }
                DurableReplica.open(destination, "publication", founder, validator = validator).use { b ->
                    val receiver = DirectorySync(SyncEndpoint(b, BlobStore(destination.resolve("chunks"))), share)
                    receiver.poll()
                    assertEquals(if (boundary == PublicationBoundary.READY_PUBLISHED) 1 else 0, b.history().size)
                    assertTrue(b.snapshot().objects.isEmpty())
                    DurableReplica.open(source, "publication", validator = validator).use { a ->
                        val publisher = DirectorySync(SyncEndpoint(a, BlobStore(source.resolve("chunks"))), share)
                        repeat(2) { publisher.publish(); receiver.poll() }
                    }
                    assertEquals(expected, b.snapshot())
                    assertEquals(2, b.history().size)
                }
            } finally { executor.shutdownNow(); root.toFile().deleteRecursively() }
        }
    }
}
