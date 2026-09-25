package ai.yaay.crdt

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

object ReplicaProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        DurableReplica.open(Path.of(args[0]), "durability", validator = MutationValidator { _, _, _, _ -> }, journalObserver = { boundary ->
            if (args[1] == "crash:${boundary.name}" || args[1] == "bootstrap:${boundary.name}") {
                println("CRASH:${boundary.name}"); System.out.flush(); Runtime.getRuntime().halt(77)
            }
        }, identityObserver = { boundary, author ->
            if (args[1] == "identity:${boundary.name}") {
                println("IDENTITY:${boundary.name}:$author"); System.out.flush(); Runtime.getRuntime().halt(78)
            }
        }).use { replica ->
            if (args[1].startsWith("bootstrap:")) {
                val batches = Files.list(Path.of(args[2])).use { files -> files.sorted().map { BatchCodec.decode(Files.readAllBytes(it)) }.toList() }
                replica.bootstrap(batches, batches.first().batch.id.author)
                error("Expected bootstrap crash boundary")
            }
            if (args[1].startsWith("crash:")) {
                replica.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT), Operation.EditText(id(0), null, "all-or-nothing"), Operation.Create(id(2), "test", Shape.REGISTER, mapOf("value" to Atom.Str("second object")))) }
                error("Expected crash boundary")
            }
            when (args[1]) {
                "commit" -> {
                    val batch = replica.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT), Operation.EditText(id(0), null, "durable🌍")) }
                    println("COMMITTED:${batch.batch.id.counter}")
                }
                "hold" -> println("LOCKED")
            }
            System.out.flush()
            if (args[1] == "hold" || args[1] == "commit") System.`in`.read()
        }
    }
}

class DurabilityTest {
    private val validator = MutationValidator { _, _, _, _ -> }
    private fun child(path: Path, mode: String, vararg extra: String): Process {
        val classpath = listOf(DurableReplica::class.java, ReplicaProcessProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        return ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, ReplicaProcessProbe::class.java.name, path.toString(), mode) + extra).redirectErrorStream(true).start()
    }
    private fun line(process: Process): String? {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<String?> { process.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    @Test fun killedWriterRetainsWholeBatchAndDoesNotReuseCounter() {
        val path = Files.createTempDirectory("yaay-process-")
        val process = child(path, "commit")
        try {
            assertEquals("COMMITTED:1", line(process))
            process.destroyForcibly()
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            DurableReplica.open(path, "durability", validator = validator).use { replica ->
                assertEquals("durable🌍", replica.snapshot().objects.values.single().text)
                assertEquals(1, replica.history().size)
                val next = replica.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.LIST)) }
                assertEquals(2L, next.batch.id.counter)
            }
        } finally { process.destroyForcibly(); path.toFile().deleteRecursively() }
    }
    @Test fun processDiesAtEveryJournalBoundaryWithoutPartialBatchOrCounterReuse() {
        for (boundary in JournalBoundary.entries.filter { it != JournalBoundary.BOOTSTRAP_INSTALLED }) {
            val path = Files.createTempDirectory("yaay-boundary-")
            try {
                DurableReplica.open(path, "durability", validator = validator).use { replica ->
                    replica.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) }
                }
                val process = child(path, "crash:${boundary.name}")
                try {
                    assertEquals("CRASH:${boundary.name}", line(process))
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                    assertEquals(77, process.exitValue())
                } finally { process.destroyForcibly() }
                DurableReplica.open(path, "durability", validator = validator).use { replica ->
                    val expectedCount = if (boundary in setOf(JournalBoundary.AFTER_CHECKSUM, JournalBoundary.AFTER_FORCE)) 2 else 1
                    assertEquals(expectedCount, replica.history().size, boundary.name)
                    assertEquals(if (expectedCount == 2) 3 else 1, replica.snapshot().objects.size, boundary.name)
                    if (expectedCount == 2) assertTrue(replica.snapshot().objects.values.any { it.text == "all-or-nothing" })
                    val committed = replica.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) }
                    assertEquals(expectedCount.toLong() + 1, committed.batch.id.counter)
                }
            } finally { path.toFile().deleteRecursively() }
        }
    }

    @Test fun bootstrapCrashInstallsAllHistoryOrNoneAndCanRetry() {
        for (boundary in JournalBoundary.entries) {
            val root = Files.createTempDirectory("yaay-bootstrap-")
            val path = root.resolve("replica")
            try {
                val signer = JvmBatchCrypto.generate()
                val author = DurableReplica.open(path, "durability", signer.author, validator = validator).use { it.author }
                val source = ReplicaEngine("durability", signer.author, signer.author, signer, MemoryJournal(), validator)
                source.commit(listOf(Operation.Membership(author, true)))
                repeat(2) { source.commit(listOf(Operation.Create(source.nextId(0), "test", Shape.TEXT))) }
                val fixture = Files.createDirectory(root.resolve("fixture"))
                source.history.forEachIndexed { index, batch -> Files.write(fixture.resolve("$index.batch"), BatchCodec.encode(batch)) }
                val process = child(path, "bootstrap:${boundary.name}", fixture.toString())
                try {
                    assertEquals("CRASH:${boundary.name}", line(process))
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                    assertEquals(77, process.exitValue())
                } finally { process.destroyForcibly() }
                DurableReplica.open(path, "durability", signer.author, validator = validator).use { recovered ->
                    assertEquals(if (boundary == JournalBoundary.BOOTSTRAP_INSTALLED) 3 else 0, recovered.history().size)
                    if (recovered.history().isEmpty()) recovered.bootstrap(source.history.reversed(), signer.author)
                    assertEquals(source.snapshot, recovered.snapshot())
                    assertEquals(1L, recovered.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) }.batch.id.counter)
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun completeFrameCorruptionFailsClosedWithoutTruncatingHistory() {
        val path = Files.createTempDirectory("yaay-corrupt-")
        try {
            DurableReplica.open(path, "durability", validator = validator).use { it.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) } }
            val journal = path.resolve("journal")
            val original = Files.readAllBytes(journal)
            val corrupt = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            Files.write(journal, corrupt)
            assertFailsWith<IllegalArgumentException> { DurableReplica.open(path, "durability", validator = validator) }
            assertContentEquals(corrupt, Files.readAllBytes(journal))
            Files.write(journal, original)
            DurableReplica.open(path, "durability", validator = validator).use { assertEquals(1, it.history().size) }
        } finally { path.toFile().deleteRecursively() }
    }

    @Test fun unsupportedBatchRemainsFrozenAcrossRestartAndDoesNotAdvanceFrontier() {
        val path = Files.createTempDirectory("yaay-unsupported-")
        try {
            val signer = JvmBatchCrypto.generate()
            val source = ReplicaEngine("durability", signer.author, signer.author, signer, MemoryJournal(), validator)
            source.commit(listOf(Operation.Create(source.nextId(0), "test", Shape.TEXT)))
            val expected = source.snapshot
            val unknown = signer.sign(Batch("durability", 1, BatchId(signer.author, 2), Frontier(mapOf(signer.author to 2)), listOf(Operation.Unknown(77, listOf(1, 2, 3)))))
            DurableReplica.open(path, "durability", signer.author, validator = validator).use {
                it.bootstrap(source.history, signer.author)
                assertEquals(IngestResult.UNSUPPORTED, it.ingest(unknown, signer.author))
            }
            DurableReplica.open(path, "durability", signer.author, validator = validator).use {
                assertTrue(it.readOnly())
                assertEquals(expected, it.snapshot())
                assertEquals(unknown, it.history().last())
                assertFailsWith<IllegalStateException> { it.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) } }
            }
        } finally { path.toFile().deleteRecursively() }
    }

    @Test fun missingIdentityWithRetainedHistoryNeverSilentlyCreatesNewKeys() {
        val path = Files.createTempDirectory("yaay-key-loss-")
        try {
            val author = DurableReplica.open(path, "durability", validator = validator).use { replica ->
                replica.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) }
                replica.author
            }
            val backup = Files.readAllBytes(path.resolve("identity"))
            Files.delete(path.resolve("identity"))
            assertFailsWith<IllegalArgumentException> { DurableReplica.open(path, "durability", validator = validator) }
            assertFalse(Files.exists(path.resolve("identity")))
            Files.write(path.resolve("identity"), backup)
            DurableReplica.open(path, "durability", validator = validator).use { assertEquals(author, it.author); assertEquals(1, it.history().size) }
        } finally { path.toFile().deleteRecursively() }
    }

    @Test fun identityInitializationCrashesNeverReusePublishedIdentityWithDifferentKeys() {
        for (boundary in IdentityBoundary.entries) {
            val path = Files.createTempDirectory("yaay-identity-boundary-")
            val process = child(path, "identity:${boundary.name}")
            try {
                val output = line(process)!!
                assertTrue(output.startsWith("IDENTITY:${boundary.name}:"), output)
                val original = output.substringAfterLast(':')
                assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(78, process.exitValue())
                DurableReplica.open(path, "durability", validator = validator).use { replica ->
                    if (boundary == IdentityBoundary.AFTER_TEMP_FORCE) assertNotEquals(original, replica.author)
                    else assertEquals(original, replica.author)
                    assertEquals(1L, replica.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) }.batch.id.counter)
                }
            } finally { process.destroyForcibly(); path.toFile().deleteRecursively() }
        }
    }

    @Test fun separateProcessCannotAcquireLiveWriterLock() {
        val path = Files.createTempDirectory("yaay-lock-")
        val process = child(path, "hold")
        try {
            assertEquals("LOCKED", line(process))
            assertFails { DurableReplica.open(path, "durability", validator = validator) }
        } finally { process.destroyForcibly(); process.waitFor(15, TimeUnit.SECONDS); path.toFile().deleteRecursively() }
    }
    @Test fun incompleteTailRepairsWithoutDiscardingCommittedState() {
        val path = Files.createTempDirectory("yaay-tail-")
        try {
            val original = DurableReplica.open(path, "durability", validator = validator).use { r ->
                r.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.REGISTER, mapOf("value" to Atom.Str("kept")))) }
                r.snapshot()
            }
            val journal = path.resolve("journal")
            val committedLength = Files.size(journal)
            for (tail in listOf(byteArrayOf(0, 0), ByteBuffer.allocate(7).putInt(200).put(byteArrayOf(1, 2, 3)).array())) {
                Files.write(journal, tail, APPEND)
                DurableReplica.open(path, "durability", validator = validator).use { assertEquals(original, it.snapshot()) }
                assertEquals(committedLength, Files.size(journal))
            }
        } finally { path.toFile().deleteRecursively() }
    }
    @Test fun concurrentCallersCommitUniqueCountersAndPrivateRootCannotSynchronize() {
        val path = Files.createTempDirectory("yaay-concurrent-")
        try {
            DurableReplica.open(path, "private", privateRoot = true, validator = validator).use { r ->
                val executor = Executors.newFixedThreadPool(4)
                try {
                    val futures = (1..24).map { executor.submit<SignedBatch> { r.commit { id, _ -> listOf(Operation.Create(id(0), "test", Shape.TEXT)) } } }
                    val counters = futures.map { it.get(20, TimeUnit.SECONDS).batch.id.counter }.sorted()
                    assertEquals((1L..24L).toList(), counters)
                    assertEquals(24, r.snapshot().objects.size)
                    assertFalse(r.canExchange(r.author))
                } finally { executor.shutdownNow() }
            }
            assertFails { DurableReplica.open(path, "private", privateRoot = false, validator = validator) }
        } finally { path.toFile().deleteRecursively() }
    }
}
