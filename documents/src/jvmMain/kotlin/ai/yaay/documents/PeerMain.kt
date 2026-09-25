package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** Headless peer entry point using the same typed mutation boundary as ordinary editors. */
public object PeerMain {
    @JvmStatic public fun main(args: Array<String>) {
        try { execute(args.toList()) } catch (failure: Exception) {
            System.err.println("${failure.javaClass.simpleName}: ${failure.message}")
            exitProcess(1)
        }
    }
    private fun execute(args: List<String>) {
        require(args.size >= 3) { "Usage: <store> <workspace> <init|init-private|info|admit|exclude|create-text|edit-text|state|sync-http|serve|publish|poll> [arguments]" }
        val path = Path.of(args[0])
        val workspace = args[1]
        val command = args[2]
        val rest = args.drop(3)
        if (command !in setOf("init", "init-private")) require(java.nio.file.Files.exists(path.resolve("identity"))) { "Initialize this replica store first" }
        val founder = if (command == "init") rest.firstOrNull() else null
        // Private-root stores have a separate explicit entry command; they cannot expose sync.
        val privateRoot = command == "init-private" || command == "private-info"
        DurableReplica.open(path, workspace, founder, privateRoot, TypedMutationValidator()).use { replica ->
            val endpoint = typedSyncEndpoint(replica, BlobStore(path.resolve("chunks")))
            fun edit(block: TypedEdit.() -> Unit): SignedBatch = replica.commit { id, snapshot ->
                TypedEdit(id, snapshot).apply(block).operations
            }
            when (command) {
                "init", "init-private", "info", "private-info" -> {
                    println("workspace=${replica.workspace}")
                    println("author=${replica.author}")
                    println("founder=${replica.founder}")
                    println("readOnly=${replica.readOnly()}")
                    println("batches=${replica.history().size}")
                }
                "admit", "exclude" -> {
                    require(rest.size == 1) { "$command requires a connection public key" }
                    val batch = replica.commit { _, _ -> listOf(Operation.Membership(rest[0], command == "admit")) }
                    println("committed=${batch.batch.id}")
                    if (command == "exclude") println("Direct exchange excluded. Signed changes from this author can still arrive through an admitted relay.")
                }
                "create-text" -> {
                    require(rest.size == 1) { "create-text requires quoted text" }
                    var created = ""
                    val batch = edit { created = create(Type.Scalar.TEXT, Value.Text(rest[0])) }
                    println("object=$created")
                    println("committed=${batch.batch.id}")
                }
                "edit-text" -> {
                    require(rest.size == 4) { "edit-text requires object ID, scalar start, scalar delete count, and quoted insertion" }
                    val batch = edit { editText(rest[0], rest[1].toInt(), rest[2].toInt(), rest[3]) }
                    println("committed=${batch.batch.id}")
                }
                "state" -> {
                    val snapshot = replica.snapshot()
                    snapshot.objects.keys.sorted().forEach { println("$it\t${snapshot.genericValue(it)}") }
                    println("frontier=${snapshot.frontier.counters.toSortedMap()}")
                }
                "sync-http" -> {
                    require(rest.size == 2) { "sync-http requires the pinned remote public key and endpoint URL" }
                    SyncClient(endpoint, rest[0], HttpExchangeConnection(URI(rest[1]))).exchange()
                    println("batches=${replica.history().size}")
                }
                "serve" -> {
                    require(rest.size == 2) { "serve requires bind address and port" }
                    HttpSyncServer(endpoint, InetSocketAddress(rest[0], rest[1].toInt())).use { server ->
                        val shutdown = CountDownLatch(1)
                        val hook = Thread { shutdown.countDown() }
                        Runtime.getRuntime().addShutdownHook(hook)
                        try {
                            println("listening=${server.address.hostString}:${server.address.port}")
                            System.out.flush()
                            shutdown.await()
                        } finally { runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
                    }
                }
                "publish", "poll" -> {
                    require(rest.size == 1) { "$command requires the mounted directory path" }
                    val sync = DirectorySync(endpoint, Path.of(rest[0]))
                    if (command == "publish") { sync.publish(); println("published=${replica.history().size}") }
                    else println(sync.poll())
                }
                else -> throw IllegalArgumentException("Unknown peer command: $command")
            }
        }
    }
}
