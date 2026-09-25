package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*

/** Includes immutable content references stored inside typed map key literals. */
public fun typedSyncEndpoint(replica: DurableReplica, blobs: BlobStore): SyncEndpoint = SyncEndpoint(replica, blobs) { snapshot ->
    val types = TypeSystem(snapshot)
    val content = mutableListOf<Atom.Blob>()
    val pending = ArrayDeque<KeyValue>()
    snapshot.objects.values.filter { it.shape == Shape.MAP }.forEach { obj ->
        val type = types.structure(TypeEncoding.decode(obj.type)) as Type.MapOf
        obj.fields.keys.forEach { pending.addLast(KeyEncoding.fromField(types, type.key, it)) }
    }
    while (pending.isNotEmpty()) {
        when (val key = pending.removeLast()) {
            is KeyValue.Atomic -> (key.atom as? Atom.Blob)?.let(content::add)
            is KeyValue.Record -> pending.addAll(key.fields.values)
            is KeyValue.Variant -> key.payload?.let(pending::addLast)
            is KeyValue.Sequence -> pending.addAll(key.items)
            is KeyValue.MapEntries -> { pending.addAll(key.entries.keys); pending.addAll(key.entries.values) }
        }
    }
    content
}
