package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

object TypedReplayProbe {
    @JvmStatic fun main(args: Array<String>) {
        DurableReplica.open(Path.of(args[0]), "replay", validator = TypedMutationValidator()).use { replica ->
            if (args[1] == "write") {
                var typeId = ""
                replica.commit { id, _ ->
                    typeId = id(0)
                    listOf(TypeEncoding.publish(typeId, TypeDefinition(emptyList(), false, Type.Record(mapOf("title" to Type.Scalar.STRING, "body" to Type.Scalar.TEXT)))))
                }
                var document = ""
                replica.commit { id, snapshot -> TypedEdit(id, snapshot).apply {
                    document = create(Type.Named(typeId), Value.Record(mapOf("title" to Value.Atomic(Atom.Str("nominal")), "body" to Value.Text("retained 🌍"))))
                }.operations }
                replica.commit { id, snapshot -> TypedEdit(id, snapshot).apply { create(Type.Ref(Type.Named(typeId)), Value.Atomic(Atom.Ref(document))) }.operations }
                replica.commit { _, _ -> listOf(Operation.Delete(document)) }
            }
            val snapshot = replica.snapshot()
            val normalized = snapshot.objects.toSortedMap().values.joinToString("\n") + "\n" + snapshot.frontier.counters.toSortedMap()
            println(BatchCodec.hash(normalized.toByteArray()))
            System.out.flush()
            if (args[1] == "write") System.`in`.read()
        }
    }
}

class PeerProcessTest {
    private fun process(vararg args: String, main: String = PeerMain::class.java.name): Process {
        val classpath = System.getProperty("yaay.test.classpath")
        return ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, main) + args).redirectErrorStream(true).start()
    }
    private fun run(vararg args: String): String {
        val child = process(*args)
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(0, child.exitValue(), output)
            return output
        } finally { child.destroyForcibly() }
    }
    @Test fun killedProcessReplaysNominalIdentityTombstonesReferencesAndCompleteFrontier() {
        val root = Files.createTempDirectory("yaay-nominal-replay-")
        val executor = Executors.newSingleThreadExecutor()
        val writer = process(root.toString(), "write", main = TypedReplayProbe::class.java.name)
        try {
            val before = executor.submit<String> { writer.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS)
            assertTrue(before.matches(Regex("[0-9a-f]{64}")), before)
            writer.destroyForcibly(); assertTrue(writer.waitFor(15, TimeUnit.SECONDS))
            val reader = process(root.toString(), "read", main = TypedReplayProbe::class.java.name)
            try {
                assertTrue(reader.waitFor(15, TimeUnit.SECONDS))
                val after = reader.inputStream.bufferedReader().readText().trim()
                assertEquals(0, reader.exitValue(), after)
                assertEquals(before, after)
            } finally { reader.destroyForcibly() }
        } finally { writer.destroyForcibly(); executor.shutdownNow(); root.toFile().deleteRecursively() }
    }

    @Test fun independentRelayProcessesPublishConcurrentlyAndReceiverDeduplicates() {
        val root = Files.createTempDirectory("yaay-relay-process-")
        try {
            val a = root.resolve("a").toString(); val b = root.resolve("b").toString(); val c = root.resolve("c").toString(); val d = root.resolve("d").toString()
            val share = root.resolve("share").toString()
            val founder = run(a, "relays", "init").lineSequence().first { it.startsWith("author=") }.substringAfter('=')
            listOf(b,c,d).forEach { path ->
                val key = run(path, "relays", "init", founder).lineSequence().first { it.startsWith("author=") }.substringAfter('=')
                run(a, "relays", "admit", key)
            }
            run(a, "relays", "create-text", "original signed history")
            run(a, "relays", "publish", share)
            listOf(b,c).forEach { run(it, "relays", "poll", share) }
            val expected = run(a, "relays", "state")
            root.resolve("share").resolve(BatchCodec.hash("relays".toByteArray())).resolve(BatchCodec.hash(founder.toByteArray())).toFile().deleteRecursively()
            val publishers = listOf(process(b, "relays", "publish", share), process(c, "relays", "publish", share))
            try { publishers.forEach { child -> assertTrue(child.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), child.inputStream.bufferedReader().readText()) } }
            finally { publishers.forEach { it.destroyForcibly() } }
            repeat(2) { run(d, "relays", "poll", share) }
            assertEquals(expected, run(d, "relays", "state"))
            assertTrue("batches=4" in run(d, "relays", "info"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun standalonePeersPublishPollAndExchangeHttpAcrossProcesses() {
        val root = Files.createTempDirectory("yaay-cli-")
        val executor = Executors.newSingleThreadExecutor()
        var server: Process? = null
        try {
            val a = root.resolve("a").toString()
            val b = root.resolve("b").toString()
            val share = root.resolve("share").toString()
            val founder = run(a, "cli", "init").lineSequence().first { it.startsWith("author=") }.substringAfter('=')
            val peer = run(b, "cli", "init", founder).lineSequence().first { it.startsWith("author=") }.substringAfter('=')
            run(a, "cli", "admit", peer)
            run(a, "cli", "create-text", "hello 🌍")
            run(a, "cli", "publish", share)
            run(b, "cli", "poll", share)
            assertEquals(run(a, "cli", "state"), run(b, "cli", "state"))
            run(b, "cli", "create-text", "offline contribution")
            server = process(a, "cli", "serve", "127.0.0.1", "0")
            val live = server
            val listening = executor.submit<String> { live.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS)
            assertTrue(listening.startsWith("listening="), listening)
            val port = listening.substringAfterLast(':')
            run(b, "cli", "sync-http", founder, "http://127.0.0.1:$port/sync")
            server.destroy()
            assertTrue(server.waitFor(15, TimeUnit.SECONDS))
            val state = run(a, "cli", "state")
            assertTrue("offline contribution" in state)
            assertEquals(state, run(b, "cli", "state"))
        } finally { server?.destroyForcibly(); executor.shutdownNow(); root.toFile().deleteRecursively() }
    }
    @Test fun embeddedGraphCommandsProjectAndRebuildWithoutChangingTheJournal() {
        val root = Files.createTempDirectory("yaay-cli-graph-")
        try {
            run(root.toString(), "cli-graph", "init")
            run(root.toString(), "cli-graph", "create-text", "projected content")
            val journal = Files.readAllBytes(root.resolve("journal"))
            assertTrue(run(root.toString(), "cli-graph", "graph-state").contains("projected content"))
            assertTrue(run(root.toString(), "cli-graph", "graph-rebuild").contains("projected content"))
            assertContentEquals(journal, Files.readAllBytes(root.resolve("journal")))
            assertTrue(Files.exists(root.resolve("graph/CURRENT")))
        } finally { root.toFile().deleteRecursively() }
    }

}
