package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.graph.*
import kotlinx.coroutines.*
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
    internal fun execute(args: List<String>, out: java.io.PrintStream = System.out, input: () -> String = { System.`in`.readBytes().decodeToString() }) {
        require(args.size >= 3) { "Usage: <store> <workspace> <init|init-private|info|admit|exclude|create-text|edit-text|state|sync-http|serve|publish|poll|graph-state|graph-rebuild|${TypedCommands.commands.joinToString("|")}> [arguments]\n\nTyped content:\n${TypedCommands.usage}" }
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
                    out.println("workspace=${replica.workspace}")
                    out.println("author=${replica.author}")
                    out.println("founder=${replica.founder}")
                    out.println("readOnly=${replica.readOnly()}")
                    out.println("batches=${replica.history().size}")
                }
                "admit", "exclude" -> {
                    require(rest.size == 1) { "$command requires a connection public key" }
                    val batch = replica.commit { _, _ -> listOf(Operation.Membership(rest[0], command == "admit")) }
                    out.println("committed=${batch.batch.id}")
                    if (command == "exclude") out.println("Direct exchange excluded. Signed changes from this author can still arrive through an admitted relay.")
                }
                "create-text" -> {
                    require(rest.size == 1) { "create-text requires quoted text" }
                    var created = ""
                    val batch = edit { created = create(Type.Scalar.TEXT, Value.Text(rest[0])) }
                    out.println("object=$created")
                    out.println("committed=${batch.batch.id}")
                }
                "edit-text" -> {
                    require(rest.size == 4) { "edit-text requires object path, scalar start, scalar delete count, and quoted insertion" }
                    val batch = replica.commit { id, snapshot ->
                        TypedEdit(id, snapshot).apply { editText(ValueSyntax(snapshot).locate(rest[0]), rest[1].toInt(), rest[2].toInt(), rest[3]) }.operations
                    }
                    out.println("committed=${batch.batch.id}")
                }
                "state" -> {
                    val snapshot = replica.snapshot()
                    snapshot.objects.keys.sorted().forEach { out.println("$it\t${snapshot.genericValue(it)}") }
                    out.println("frontier=${snapshot.frontier.counters.toSortedMap()}")
                }
                "graph-state", "graph-rebuild" -> runBlocking {
                    val projection = GraphProjection(replica, typedGraphStore(path.resolve("graph"), replica), this)
                    try {
                        // CLI commands show the complete current local state, not an arbitrary lagging cache.
                        replica.history().lastOrNull { it.batch.version == 1 && it.batch.operations.none { op -> op is Operation.Unknown } }
                            ?.let { withTimeout(30_000) { projection.await(it.commitToken()) } }
                        if (command == "graph-rebuild") projection.rebuild()
                        val query = GraphQuery.Objects()
                        fun printResult(result: GraphResult) {
                            result.objects.forEach { out.println("${it.id}\t${it.type}\t${it.text}") }
                            out.println("frontier=${result.checkpoint.frontier.counters.toSortedMap()}")
                        }
                        printResult(withTimeout(30_000) { projection.read(query) })
                    } finally { projection.close() }
                }
                "sync-http" -> {
                    require(rest.size == 2) { "sync-http requires the pinned remote public key and endpoint URL" }
                    SyncClient(endpoint, rest[0], HttpExchangeConnection(URI(rest[1]))).exchange()
                    out.println("batches=${replica.history().size}")
                }
                "serve" -> {
                    require(rest.size == 2) { "serve requires bind address and port" }
                    HttpSyncServer(endpoint, InetSocketAddress(rest[0], rest[1].toInt())).use { server ->
                        val shutdown = CountDownLatch(1)
                        val hook = Thread { shutdown.countDown() }
                        Runtime.getRuntime().addShutdownHook(hook)
                        try {
                            out.println("listening=${server.address.hostString}:${server.address.port}")
                            System.out.flush()
                            shutdown.await()
                        } finally { runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
                    }
                }
                "publish", "poll" -> {
                    require(rest.size == 1) { "$command requires the mounted directory path" }
                    val sync = DirectorySync(endpoint, Path.of(rest[0]))
                    if (command == "publish") { sync.publish(); out.println("published=${replica.history().size}") }
                    else out.println(sync.poll())
                }
                in TypedCommands.commands -> TypedCommands.run(command, rest, replica, endpoint.blobs, out, input)
                else -> throw IllegalArgumentException("Unknown peer command: $command")
            }
        }
    }
}
