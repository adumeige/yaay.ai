package ai.yaay.crdt

// Kotlin read-only collection interfaces do not prevent a caller retaining a mutable backing
// collection. Detach at every ownership boundary, including results returned to consumers.
internal fun Atom.detached(): Atom = when (this) {
    is Atom.Blob -> copy(chunks = chunks.toList())
    else -> this
}
internal fun Operation.detached(): Operation = when (this) {
    is Operation.Create -> copy(fields = fields.mapValues { it.value.detached() })
    is Operation.Assign -> copy(value = value.detached())
    is Operation.EditText -> copy(delete = delete.toSet())
    is Operation.Unknown -> copy(payload = payload.toList())
    else -> this
}
internal fun SignedBatch.detached(): SignedBatch = copy(
    batch = batch.copy(vector = Frontier(batch.vector.counters.toMap()), operations = batch.operations.map { it.detached() }),
    signature = signature.toList(),
)
internal fun Snapshot.detached(): Snapshot = copy(
    frontier = Frontier(frontier.counters.toMap()),
    objects = objects.mapValues { (_, obj) -> obj.copy(fields = obj.fields.mapValues { it.value.detached() }, children = obj.children.toList(), textPositions = obj.textPositions.toList()) },
)
