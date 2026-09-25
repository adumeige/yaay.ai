package ai.yaay.crdt

/** Counters describe complete applied prefixes, never pending messages. */
public data class Frontier(public val counters: Map<String, Long> = emptyMap()) {
    public operator fun get(author: String): Long = counters[author] ?: 0L
    public fun contains(id: BatchId): Boolean = get(id.author) >= id.counter
    public fun before(batch: Batch): Frontier = Frontier(batch.vector.counters + (batch.id.author to batch.id.counter - 1))
}

public data class BatchId(public val author: String, public val counter: Long)
public data class OpId(public val batch: BatchId, public val index: Int) : Comparable<OpId> {
    override fun compareTo(other: OpId): Int = compareValuesBy(this, other, { it.batch.author }, { it.batch.counter }, { it.index })
    public fun stableId(): String = "${batch.author}:${batch.counter}:$index"
}

/** Values in atomic registers. Embedded instances and references intentionally differ. */
public sealed interface Atom {
    public data class Bool(public val value: Boolean) : Atom
    public data class Number(public val value: Double) : Atom
    public data class Str(public val value: String) : Atom
    public data class Instance(public val id: String) : Atom
    public data class Ref(public val id: String) : Atom
    public data class Variant(public val tag: String, public val payload: String?) : Atom
    public data class Blob(public val hash: String, public val size: Long, public val chunks: List<String>) : Atom
}

public enum class Shape { REGISTER, RECORD, MAP, LIST, TEXT, SUM }

public sealed interface Operation {
    /** IDs must be derived from this operation's ID, preventing concurrent identity collisions. */
    public data class Create(public val id: String, public val type: String, public val shape: Shape, public val fields: Map<String, Atom> = emptyMap()) : Operation
    public data class Assign(public val target: String, public val field: String, public val value: Atom) : Operation
    public data class Place(public val target: String, public val parent: String?, public val after: String? = null) : Operation
    public data class Delete(public val target: String) : Operation
    /** Text positions are Unicode scalar values, with stable IDs "$operationId/$scalarIndex". */
    public data class EditText(public val target: String, public val after: String?, public val insert: String, public val delete: Set<String> = emptySet()) : Operation
    public data class Membership(public val peer: String, public val admitted: Boolean) : Operation
    /** Opaque unsupported payloads are retained and freeze the entire workspace. */
    public data class Unknown(public val tag: Int, public val payload: List<Byte>) : Operation
}

public data class Batch(
    public val workspace: String,
    public val version: Int,
    public val id: BatchId,
    public val vector: Frontier,
    public val operations: List<Operation>,
)

public data class SignedBatch(public val batch: Batch, public val digest: String, public val signature: List<Byte>)

public interface BatchCrypto {
    public fun sign(batch: Batch): SignedBatch
    public fun verify(batch: SignedBatch): Boolean
}

public interface BatchJournal {
    public fun read(): List<SignedBatch>
    /** Must durably append a whole frame before returning, or throw without acknowledging it. */
    public fun append(batch: SignedBatch)
    /** Install verified initial history atomically; valid only for an empty journal. */
    public fun initialize(batches: List<SignedBatch>)
}

public class MemoryJournal : BatchJournal {
    private val batches: MutableList<SignedBatch> = mutableListOf()
    override fun read(): List<SignedBatch> = batches.map { it.detached() }
    override fun append(batch: SignedBatch) { batches.add(batch.detached()) }
    override fun initialize(batches: List<SignedBatch>) { check(this.batches.isEmpty()); this.batches.addAll(batches.map { it.detached() }) }
}

public class ProtocolFault(message: String) : IllegalArgumentException(message)
public class UnsupportedProtocol(message: String) : IllegalStateException(message)

internal data class Event(val id: OpId, val vector: Frontier, val operation: Operation) {
    fun laterThan(other: Event): Boolean = if (id.batch == other.id.batch) id.index > other.id.index else vector.contains(other.id.batch)
}

public data class ResolvedObject(
    public val id: String,
    public val type: String,
    public val shape: Shape,
    public val fields: Map<String, Atom>,
    public val deleted: Boolean,
    public val parent: String?,
    public val children: List<String>,
    public val text: String,
    public val textPositions: List<String>,
    public val embeddedOwner: String? = null,
)

public data class Snapshot(public val objects: Map<String, ResolvedObject>, public val frontier: Frontier) {
    public operator fun get(id: String): ResolvedObject = objects[id] ?: throw ProtocolFault("Unknown object $id")
}

/** Pure, retained-history resolver. No arrival order, wall clock or platform APIs enter merge. */
public object Resolver {
    internal fun events(batches: Collection<Batch>): List<Event> = batches.flatMap { b ->
        b.operations.mapIndexed { i, op -> Event(OpId(b.id, i), b.vector, op) }
    }
    private fun maximal(events: List<Event>): List<Event> = events.filter { e -> events.none { it.laterThan(e) } }
    private fun winner(events: List<Event>): Event? = maximal(events).maxByOrNull { it.id }

    private fun causalOrder(events: List<Event>): List<Event> {
        val remaining = events.toMutableList()
        return buildList {
            while (remaining.isNotEmpty()) {
                val layer = maximal(remaining).sortedByDescending { it.id }
                addAll(layer)
                remaining.removeAll(layer.toSet())
            }
        }
    }

    public fun resolve(batches: Collection<Batch>): Snapshot {
        val events = events(batches)
        val creates = events.filter { it.operation is Operation.Create }.associateBy { (it.operation as Operation.Create).id }
        val deleted = events.mapNotNull { (it.operation as? Operation.Delete)?.target }.toSet()
        // An embedded instance keeps its original owner even after replacement retires it.
        val embeddedOwners = events.flatMap { event ->
            val fields = when (val op = event.operation) {
                is Operation.Create -> op.fields.map { (field, atom) -> Triple(op.id, field, atom) }
                is Operation.Assign -> listOf(Triple(op.target, op.field, op.value))
                else -> emptyList()
            }
            fields.mapNotNull { (owner, field, atom) ->
                val child = when (atom) { is Atom.Instance -> atom.id; is Atom.Variant -> atom.payload; else -> null }
                child?.let { it to (owner to field) }
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, owners) ->
            owners.minWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).first
        }
        val placements = events.filter { it.operation is Operation.Place }.groupBy { (it.operation as Operation.Place).target }
        // Rank causal maxima first; among concurrent moves the smallest destination wins.
        val choices = placements.mapValues { (_, history) ->
            val remaining = history.toMutableList()
            buildList {
                while (remaining.isNotEmpty()) {
                    val layer = maximal(remaining).sortedWith(compareBy<Event> { (it.operation as Operation.Place).parent ?: "" }.thenByDescending { it.id })
                    addAll(layer)
                    remaining.removeAll(layer.toSet())
                }
            }
        }
        val selected = choices.mapValues { 0 }.toMutableMap()
        fun placement(id: String): Event? = choices[id]?.getOrNull(selected[id] ?: 0)
        fun parent(id: String): String? = embeddedOwners[id] ?: (placement(id)?.operation as? Operation.Place)?.parent
        // Resolve every cycle by discarding its greatest operation ID and trying prior placement.
        while (true) {
            var cycle: List<String>? = null
            for (start in creates.keys.sorted()) {
                val path = mutableListOf<String>()
                var node: String? = start
                while (node != null) {
                    val index = path.indexOf(node)
                    if (index >= 0) { cycle = path.drop(index); break }
                    path.add(node)
                    node = parent(node)
                }
                if (cycle != null) break
            }
            val found = cycle ?: break
            val movable = found.filter { it !in embeddedOwners && placement(it) != null }
            require(movable.isNotEmpty()) { "Cyclic embedded instances" }
            val discard = movable.maxBy { placement(it)!!.id }
            selected[discard] = (selected[discard] ?: 0) + 1
        }
        fun orderedChildren(owner: String): List<String> {
            val children = creates.keys.filter { it !in embeddedOwners && parent(it) == owner }.toSet()
            val anchors = children.associateWith { child ->
                (placement(child)?.operation as? Operation.Place)?.after?.takeIf { it in children && it != child }
            }.toMutableMap()
            // Concurrent reorders can cycle through sibling anchors as well as parents.
            for (start in children.sorted()) {
                val path = mutableListOf<String>()
                var next: String? = start
                while (next != null) {
                    val index = path.indexOf(next)
                    if (index >= 0) { anchors[path.drop(index).maxOrNull()!!] = null; break }
                    path.add(next)
                    next = anchors[next]
                }
            }
            val result = mutableListOf<String>()
            val successors = children.groupBy { anchors[it] }.mapValues { (_, items) ->
                val order = causalOrder(items.map { placement(it)!! }).mapIndexed { index, event -> event.id to index }.toMap()
                items.sortedBy { order.getValue(placement(it)!!.id) }
            }
            val stack = ArrayDeque<String>()
            successors[null].orEmpty().asReversed().forEach(stack::addLast)
            while (stack.isNotEmpty()) {
                val item = stack.removeLast()
                if (item !in deleted) result.add(item)
                successors[item].orEmpty().asReversed().forEach(stack::addLast)
            }
            return result
        }
        val objects = creates.mapValues { (id, creation) ->
            val op = creation.operation as Operation.Create
            val writes = events.filter { (it.operation as? Operation.Assign)?.target == id }
            val fields = (op.fields.keys + writes.map { (it.operation as Operation.Assign).field }).associateWith { field ->
                val candidates = writes.filter { (it.operation as Operation.Assign).field == field } + if (field in op.fields) listOf(creation) else emptyList()
                when (val change = winner(candidates)!!.operation) {
                    is Operation.Create -> change.fields.getValue(field)
                    is Operation.Assign -> change.value
                    else -> error("Not a register write")
                }
            }
            data class Character(val id: String, val after: String?, val value: String, val event: Event, val offset: Int)
            val chars = mutableListOf<Character>()
            val removed = mutableSetOf<String>()
            events.filter { (it.operation as? Operation.EditText)?.target == id }.forEach { e ->
                val edit = e.operation as Operation.EditText
                removed.addAll(edit.delete)
                var anchor = edit.after
                unicodeScalars(edit.insert).forEachIndexed { index, scalar ->
                    val position = "${e.id.stableId()}/$index"
                    chars.add(Character(position, anchor, scalar, e, index))
                    anchor = position
                }
            }
            val visible = mutableListOf<Character>()
            val successors = chars.groupBy { it.after }.mapValues { (_, items) ->
                val order = causalOrder(items.map { it.event }).mapIndexed { index, event -> event.id to index }.toMap()
                items.sortedWith(compareBy<Character> { order.getValue(it.event.id) }.thenBy { it.offset })
            }
            val stack = ArrayDeque<Character>()
            successors[null].orEmpty().asReversed().forEach(stack::addLast)
            while (stack.isNotEmpty()) {
                val character = stack.removeLast()
                if (character.id !in removed) visible.add(character)
                successors[character.id].orEmpty().asReversed().forEach(stack::addLast)
            }
            ResolvedObject(id, op.type, op.shape, fields, id in deleted, parent(id), orderedChildren(id), visible.joinToString("") { it.value }, visible.map { it.id }, embeddedOwners[id])
        }
        return Snapshot(objects, Frontier(batches.groupBy { it.id.author }.mapValues { (_, bs) -> bs.maxOf { it.id.counter } }))
    }
}

/** Reject unpaired UTF-16 surrogates; offsets are explicitly Unicode scalar indices. */
public fun unicodeScalars(text: String): List<String> = buildList {
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        when {
            c.isHighSurrogate() -> {
                require(i < text.length && text[i].isLowSurrogate()) { "Unpaired high surrogate" }
                add("$c${text[i++]}")
            }
            c.isLowSurrogate() -> throw IllegalArgumentException("Unpaired low surrogate")
            else -> add(c.toString())
        }
    }
}
