package ai.yaay.crdt

/** The typed document layer validates both local proposals and received author snapshots here. */
public fun interface MutationValidator {
    public fun validate(author: String, before: Snapshot, after: Snapshot, operations: List<Operation>)
}

public enum class IngestResult { APPLIED, BUFFERED, DUPLICATE, UNSUPPORTED }

/** Portable state machine. Its host must serialize calls and exclusively own its journal. */
public class ReplicaEngine(
    public val workspace: String,
    public val author: String,
    public val founder: String,
    private val crypto: BatchCrypto,
    private val journal: BatchJournal,
    private val validator: MutationValidator,
    public val privateRoot: Boolean = false,
) {
    private val accepted: MutableMap<BatchId, SignedBatch> = linkedMapOf()
    private val pending: MutableMap<BatchId, SignedBatch> = linkedMapOf()
    private val rejected: MutableMap<BatchId, Pair<SignedBatch, String>> = linkedMapOf()
    public val rejections: Map<BatchId, String> get() = rejected.mapValues { it.value.second }
    private var unsupported: SignedBatch? = null
    public val readOnly: Boolean get() = unsupported != null
    private var resolved: Snapshot = Resolver.resolve(emptyList())
    public val snapshot: Snapshot get() = resolved.detached()
    public val history: List<SignedBatch> get() = (accepted.values.toList() + listOfNotNull(unsupported)).map { it.detached() }
    public val pendingIds: Set<BatchId> get() = pending.keys.toSet()

    init {
        require(workspace.isNotBlank() && author.isNotBlank() && founder.isNotBlank())
        journal.read().forEach { receive(it, persist = false) }
    }

    public fun nextId(index: Int): String = OpId(BatchId(author, resolved.frontier[author] + 1), index).stableId()

    public fun commit(operations: List<Operation>): SignedBatch {
        check(!readOnly) { "Workspace needs a protocol upgrade" }
        require(isEverAdmitted(author, accepted.values.map { it.batch })) { "Local author has not been admitted" }
        val counter = resolved.frontier[author] + 1
        val batch = Batch(workspace, 1, BatchId(author, counter), Frontier(resolved.frontier.counters + (author to counter)), operations.map { it.detached() })
        // Invalid local proposals never consume or quarantine an author counter.
        validate(batch, accepted.values.map { it.batch })
        val signed = crypto.sign(batch)
        require(signed.batch == batch && crypto.verify(signed)) { "Signer does not match replica identity" }
        check(receive(signed, true) == IngestResult.APPLIED)
        return signed
    }

    /** Direct transport membership is checked separately from historical batch authorship. */
    public fun canExchange(peer: String): Boolean = !privateRoot && isActive(peer, accepted.values.map { it.batch })

    public fun ingest(batch: SignedBatch, publisher: String): IngestResult {
        if (!canExchange(publisher)) throw ProtocolFault("Direct peer is not admitted: $publisher")
        return receive(batch, true)
    }

    /** Full-history join verifies membership from the pinned founder before trusting the relay. */
    public fun bootstrap(batches: List<SignedBatch>, publisher: String) {
        check(!privateRoot && accepted.isEmpty() && pending.isEmpty() && unsupported == null)
        val trial = ReplicaEngine(workspace, author, founder, crypto, MemoryJournal(), validator)
        batches.forEach { trial.receive(it, false) }
        require(trial.pending.isEmpty() && trial.canExchange(publisher)) { "Incomplete or unadmitted bootstrap relay" }
        journal.initialize(trial.history)
        trial.history.forEach { receive(it, false) }
    }

    private fun isEverAdmitted(peer: String, batches: Collection<Batch>): Boolean = peer == founder ||
        batches.any { b -> b.operations.any { it is Operation.Membership && it.peer == peer && it.admitted } }

    private fun isActive(peer: String, batches: Collection<Batch>): Boolean = isEverAdmitted(peer, batches) &&
        batches.none { b -> b.operations.any { it is Operation.Membership && it.peer == peer && !it.admitted } }

    private fun receive(incoming: SignedBatch, persist: Boolean): IngestResult {
        val signed = incoming.detached()
        val b = signed.batch
        if (b.workspace != workspace || !crypto.verify(signed)) throw ProtocolFault("Wrong workspace or invalid signature")
        if (b.id.counter <= 0 || b.vector[b.id.author] != b.id.counter || b.vector.counters.any { it.value < 0 }) throw ProtocolFault("Malformed causal vector")
        val existing = accepted[b.id] ?: pending[b.id] ?: rejected[b.id]?.first ?: unsupported?.takeIf { it.batch.id == b.id }
        if (existing != null) {
            if (existing != signed) throw ProtocolFault("Conflicting content for ${b.id}")
            rejected[b.id]?.let { throw ProtocolFault(it.second) }
            return IngestResult.DUPLICATE
        }
        pending[b.id] = signed
        drain(persist)
        rejected[b.id]?.let { throw ProtocolFault(it.second) }
        return when {
            b.id in accepted -> IngestResult.APPLIED
            unsupported?.batch?.id == b.id -> IngestResult.UNSUPPORTED
            else -> IngestResult.BUFFERED
        }
    }

    private fun ready(b: Batch): Boolean = b.vector.counters.all { (peer, n) ->
        resolved.frontier[peer] >= if (peer == b.id.author) n - 1 else n
    }

    private fun drain(persist: Boolean) {
        while (!readOnly) {
            val signed = pending.values.firstOrNull { ready(it.batch) } ?: return
            val b = signed.batch
            val context = accepted.values.map { it.batch }.filter { it.id.counter <= (if (it.id.author == b.id.author) b.id.counter - 1 else b.vector[it.id.author]) }
            try {
            // The vector must include the transitive closure of every claimed observation.
            if (context.any { prior -> prior.vector.counters.any { (key, n) -> n > (if (key == b.id.author) b.id.counter - 1 else b.vector[key]) } }) {
                pending.remove(b.id)
                throw ProtocolFault("Non-transitive causal context")
            }
            if (!isEverAdmitted(b.id.author, context)) {
                pending.remove(b.id)
                throw ProtocolFault("Never-admitted author ${b.id.author}")
            }
            if (b.version != 1 || b.operations.any { it is Operation.Unknown }) {
                if (persist) journal.append(signed)
                unsupported = signed
                pending.remove(b.id)
                return
            }
                validate(b, context)
            } catch (failure: IllegalArgumentException) {
                pending.remove(b.id)
                rejected[b.id] = signed to (failure.message ?: "Invalid batch")
                continue
            }
            if (persist) journal.append(signed)
            accepted[b.id] = signed
            pending.remove(b.id)
            resolved = Resolver.resolve(accepted.values.map { it.batch })
        }
    }

    private fun validate(batch: Batch, context: List<Batch>) {
        require(batch.operations.isNotEmpty()) { "Empty batch" }
        val before = Resolver.resolve(context)
        val ids = before.objects.keys.toMutableSet()
        val deleted = before.objects.values.filter { it.deleted }.map { it.id }.toMutableSet()
        val staged = mutableListOf<Operation>()
        fun current(): Snapshot = Resolver.resolve(context + batch.copy(operations = staged.toList()))
        fun live(id: String): ResolvedObject {
            require(id in ids && id !in deleted) { "Missing or deleted target $id" }
            return current()[id]
        }
        batch.operations.forEachIndexed { index, op ->
            when (op) {
                is Operation.Create -> {
                    require(op.id == OpId(batch.id, index).stableId() && op.id !in ids) { "Creation requires a fresh operation-derived identity" }
                    require(op.type.isNotBlank())
                    ids.add(op.id)
                }
                is Operation.Assign -> {
                    val obj = live(op.target)
                    require(obj.shape != Shape.TEXT && obj.shape != Shape.LIST) { "Assign cannot replace sequence contents" }
                    if (obj.shape == Shape.REGISTER || obj.shape == Shape.SUM) require(op.field == "value")
                }
                is Operation.Delete -> { live(op.target); deleted.add(op.target) }
                is Operation.Place -> {
                    live(op.target)
                    op.parent?.let { require(live(it).shape == Shape.LIST) { "Placement requires a list/container" } }
                    require(op.parent != op.target)
                    if (op.after != null) {
                        val anchor = live(op.after)
                        require(anchor.parent == op.parent && op.after != op.target && op.parent != null)
                    }
                    var ancestor = op.parent
                    while (ancestor != null) {
                        require(ancestor != op.target) { "Containment cycle" }
                        ancestor = current()[ancestor].parent
                    }
                }
                is Operation.EditText -> {
                    require(live(op.target).shape == Shape.TEXT)
                    unicodeScalars(op.insert)
                    // Deleted anchors remain usable when creating edits from retained history.
                    val positions = Resolver.events(context + batch.copy(operations = staged.toList()))
                        .filter { (it.operation as? Operation.EditText)?.target == op.target }
                        .flatMap { e -> unicodeScalars((e.operation as Operation.EditText).insert).indices.map { "${e.id.stableId()}/$it" } }.toSet()
                    require(op.after == null || op.after in positions) { "Unknown text anchor" }
                    require(positions.containsAll(op.delete)) { "Unknown deleted text position" }
                }
                is Operation.Membership -> {
                    require(op.peer.isNotBlank())
                    require(isActive(batch.id.author, context)) { "Excluded signer cannot change membership after observing exclusion" }
                    if (!op.admitted) require(isEverAdmitted(op.peer, context))
                }
                is Operation.Unknown -> throw UnsupportedProtocol("Unknown operation")
            }
            staged.add(op)
        }
        val after = current()
        after.objects.values.filter { !it.deleted }.forEach { obj ->
            obj.fields.values.forEach { atom ->
                when (atom) {
                    is Atom.Number -> require(atom.value.isFinite())
                    is Atom.Str -> unicodeScalars(atom.value)
                    is Atom.Instance -> require(atom.id in ids)
                    is Atom.Ref -> require(atom.id in ids) // Known tombstones remain broken references.
                    is Atom.Variant -> require(atom.payload == null || atom.payload in ids)
                    is Atom.Blob -> require(atom.size >= 0 && atom.hash.matches(Regex("[0-9a-f]{64}")) && atom.chunks.all { it.matches(Regex("[0-9a-f]{64}")) })
                    is Atom.Bool -> Unit
                }
            }
        }
        validator.validate(batch.id.author, before, after, batch.operations)
    }
}
