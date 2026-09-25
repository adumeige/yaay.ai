package ai.yaay.graph

import ai.yaay.crdt.*
import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.api.YourTracks
import com.jetbrains.youtrackdb.api.YouTrackDB
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource
import org.apache.commons.configuration2.BaseConfiguration
import org.apache.tinkerpop.gremlin.structure.Vertex
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*

public enum class ProjectionBoundary { AFTER_OBJECTS, BEFORE_CHECKPOINT, BEFORE_COMMIT, AFTER_COMMIT, BEFORE_INSTALL, AFTER_INSTALL }

/** Embedded disk database. No server or network listener; all access is serialized locally. */
public class EmbeddedYouTrackGraph(
    private val directory: Path,
    override val workspace: String,
    override val founder: String,
    private val additionalReferences: (Snapshot) -> Map<String, Set<String>> = { emptyMap() },
    private val observe: (ProjectionBoundary) -> Unit = {},
) : GraphStore {
    private val lockChannel = FileChannel.open(directory.toAbsolutePath().normalize().also { Files.createDirectories(it) }.resolve("projection.lock"), CREATE, WRITE)
    private val lock = try { lockChannel.tryLock() ?: error("Projection already has a live owner") }
        catch (failure: Throwable) { lockChannel.close(); throw failure }
    private var database: YouTrackDB? = null
    private var openingFailure: Exception? = null
    private var closed = false
    init {
        try {
            // Bind the disposable cache outside the database so corruption recovery cannot
            // silently reuse another workspace's directory.
            val binding = java.util.Base64.getEncoder().encodeToString(workspace.toByteArray()) + "\n" +
                java.util.Base64.getEncoder().encodeToString(founder.toByteArray())
            val identity = directory.resolve("binding")
            if (Files.exists(identity)) require(Files.readString(identity) == binding) { "Projection identity mismatch" }
            else installFile(identity, binding)
            val generation = if (Files.exists(directory.resolve("CURRENT"))) Files.readString(directory.resolve("CURRENT")) else "initial"
            require(generation == "initial" || generation.matches(Regex("generation-[0-9a-f-]{36}"))) { "Invalid graph generation" }
            try { database = openDatabase(directory.resolve(generation)) }
            catch (failure: Exception) { openingFailure = failure }
        } catch (failure: Throwable) { lock.release(); lockChannel.close(); throw failure }
    }
    private fun installFile(path: Path, text: String) {
        val temp = Files.createTempFile(directory, ".install-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(text.toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory, READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temp) }
    }
    private fun openDatabase(path: Path): YouTrackDB {
        Files.createDirectories(path)
        val config = BaseConfiguration()
        config.setProperty("youtrackdb.storage.diskCache.bufferSize", 64)
        val db = YourTracks.instance(path.toAbsolutePath().normalize(), config)
        try {
            if (!db.exists("projection")) db.create("projection", DatabaseType.DISK, "reader", "local-projection", "admin")
            db.openTraversal("projection", "reader", "local-projection").use { g ->
                g.executeInTx<Exception> { tx ->
                    tx.command("CREATE CLASS YaayObject IF NOT EXISTS EXTENDS V")
                    tx.command("CREATE CLASS YaayCheckpoint IF NOT EXISTS EXTENDS V")
                    tx.command("CREATE CLASS YaayContains IF NOT EXISTS EXTENDS E")
                    tx.command("CREATE CLASS YaayReferences IF NOT EXISTS EXTENDS E")
                    tx.command("CREATE PROPERTY YaayObject.objectId IF NOT EXISTS STRING")
                    tx.command("CREATE INDEX YaayObject.objectId IF NOT EXISTS ON YaayObject (objectId) UNIQUE")
                    tx.command("CREATE PROPERTY YaayObject.typeName IF NOT EXISTS STRING")
                    tx.command("CREATE INDEX YaayObject.typeName IF NOT EXISTS ON YaayObject (typeName) NOTUNIQUE")
                    val meta = tx.V().hasLabel("YaayCheckpoint").toList()
                    if (meta.isEmpty()) {
                        tx.addV("YaayCheckpoint").property("workspace", workspace).property("founder", founder)
                            .property("ready", false).iterate()
                    } else {
                        require(meta.size == 1 && meta.single().value<String>("workspace") == workspace &&
                            meta.single().value<String>("founder") == founder) { "Projection belongs to a different workspace/founder" }
                    }
                }
            }
            return db
        } catch (failure: Throwable) { runCatching { db.close() }; throw failure }
    }
    /** Build in a separate generation, flush it, then atomically switch the local pointer. */
    @Synchronized override fun rebuild(snapshot: Snapshot) {
        check(!closed)
        val previous = database
        val generation = "generation-${java.util.UUID.randomUUID()}"
        var staged: YouTrackDB? = null
        try {
            staged = openDatabase(directory.resolve(generation))
            database = staged
            replace(snapshot)
            staged.close() // flush the staged cache before making it the restart target
            staged = openDatabase(directory.resolve(generation))
            database = staged
            check(checkpoint()?.frontier == snapshot.frontier) { "Staged graph did not survive reopening" }
            observe(ProjectionBoundary.BEFORE_INSTALL)
            installFile(directory.resolve("CURRENT"), generation)
        } catch (failure: Throwable) {
            database = previous
            runCatching { staged?.close() }
            throw failure
        }
        openingFailure = null
        runCatching { previous?.close() }
        observe(ProjectionBoundary.AFTER_INSTALL)
    }
    private fun <T> traversal(block: (YTDBGraphTraversalSource) -> T): T {
        check(!closed) { "Graph store closed" }
        return (database ?: throw IllegalStateException("Graph cache needs rebuilding", openingFailure)).openTraversal("projection", "reader", "local-projection").use(block)
    }
    private fun checkpoint(tx: YTDBGraphTraversalSource): GraphCheckpoint? {
        val meta = tx.V().hasLabel("YaayCheckpoint").next()
        return if (meta.value<Boolean>("ready")) GraphCheckpoint(workspace, ProjectionCodec.frontier(meta.value<String>("frontier"))) else null
    }
    @Synchronized override fun checkpoint(): GraphCheckpoint? = if (database == null) null else traversal { g -> g.computeInTx<Exception, GraphCheckpoint?> { checkpoint(it) } }

    @Synchronized override fun replace(snapshot: Snapshot) {
        traversal { g ->
            g.executeInTx<Exception> { tx ->
                // V1 deliberately replaces the materialized snapshot in a single transaction.
                // This also makes retry and explicit rebuild idempotent.
                tx.V().hasLabel("YaayObject").drop().iterate()
                val vertices = snapshot.objects.values.associate { obj ->
                    obj.id to tx.addV("YaayObject").property("objectId", obj.id).property("typeName", obj.type)
                        .property("deleted", obj.deleted).property("payload", ProjectionCodec.objectValue(obj)).next()
                }
                observe(ProjectionBoundary.AFTER_OBJECTS)
                snapshot.objects.values.forEach { obj ->
                    val source = vertices.getValue(obj.id)
                    obj.children.forEachIndexed { position, child ->
                        vertices[child]?.let { source.addEdge("YaayContains", it, "position", position) }
                    }
                    obj.fields.forEach { (field, atom) ->
                        val target = when (atom) {
                            is Atom.Ref -> atom.id
                            is Atom.Instance -> atom.id
                            is Atom.Variant -> atom.payload
                            else -> null
                        }
                        if (target != null) vertices[target]?.let {
                            source.addEdge(if (atom is Atom.Ref) "YaayReferences" else "YaayContains", it, "field", field)
                        }
                    }
                }
                additionalReferences(snapshot).forEach { (source, targets) ->
                    targets.forEach { target ->
                        vertices[source]?.let { from -> vertices[target]?.let { to -> from.addEdge("YaayReferences", to) } }
                    }
                }
                observe(ProjectionBoundary.BEFORE_CHECKPOINT)
                val meta = tx.V().hasLabel("YaayCheckpoint").next()
                meta.property("frontier", ProjectionCodec.frontier(snapshot.frontier)); meta.property("ready", true)
                observe(ProjectionBoundary.BEFORE_COMMIT)
            }
        }
        check(checkpoint()?.frontier == snapshot.frontier) { "YouTrackDB did not install the complete projection checkpoint" }
        observe(ProjectionBoundary.AFTER_COMMIT)
    }
    private fun decode(vertex: Vertex): ResolvedObject = ProjectionCodec.objectValue(vertex.value<String>("payload"))
    @Synchronized override fun query(query: GraphQuery): GraphResult = traversal { g ->
        g.computeInTx<Exception, GraphResult> { tx ->
            val checkpoint = checkpoint(tx) ?: error("Graph has not been projected yet")
            val objects = when (query) {
                is GraphQuery.ObjectById -> tx.V().hasLabel("YaayObject").has("objectId", query.id).toList().map(::decode)
                is GraphQuery.Objects -> {
                    var traversal = tx.V().hasLabel("YaayObject")
                    if (query.type != null) traversal = traversal.has("typeName", query.type)
                    if (!query.includeDeleted) traversal = traversal.has("deleted", false)
                    traversal.toList().map(::decode)
                }
                is GraphQuery.Children -> {
                    val parent = tx.V().hasLabel("YaayObject").has("objectId", query.parent).toList().singleOrNull()?.let(::decode)
                    if (parent == null || parent.deleted) emptyList() else {
                        val children = tx.V().hasLabel("YaayObject").has("objectId", query.parent).out("YaayContains").toList().map(::decode).associateBy { it.id }
                        parent.children.mapNotNull(children::get)
                    }
                }
                is GraphQuery.Referrers -> tx.V().hasLabel("YaayObject").has("objectId", query.target)
                    .`in`("YaayReferences").dedup().has("deleted", false).toList().map(::decode)
                GraphQuery.Roots -> tx.V().hasLabel("YaayObject").has("deleted", false).toList().map(::decode).filter { it.parent == null }
                is GraphQuery.TextSearch -> tx.V().hasLabel("YaayObject").has("deleted", false).toList().map(::decode).filter { obj ->
                    obj.text.contains(query.text, ignoreCase = true) || obj.fields.values.any { it is Atom.Str && it.value.contains(query.text, ignoreCase = true) }
                }
            }
            GraphResult(checkpoint, if (query is GraphQuery.Children) objects else objects.sortedBy { it.id })
        }
    }
    @Synchronized override fun close() { if (!closed) {
        closed = true
        try { database?.close() } finally { try { lock.release() } finally { lockChannel.close() } }
    } }
}
