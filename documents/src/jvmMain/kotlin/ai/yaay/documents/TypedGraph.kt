package ai.yaay.documents

import ai.yaay.crdt.*
import ai.yaay.documents.types.*
import ai.yaay.graph.*
import java.nio.file.Path

/** Embedded V1 adapter, including references inside immutable typed map keys. */
public fun typedGraphStore(directory: Path, replica: DurableReplica): GraphStore = EmbeddedYouTrackGraph(
    directory, replica.workspace, replica.founder, additionalReferences = { snapshot ->
        val types = TypeSystem(snapshot)
        snapshot.objects.values.filter { it.shape == Shape.MAP }.associate { obj ->
            val type = types.structure(TypeEncoding.decode(obj.type)) as Type.MapOf
            val pending = ArrayDeque(obj.fields.keys.map { KeyEncoding.fromField(types, type.key, it) })
            val targets = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                when (val key = pending.removeLast()) {
                    is KeyValue.Atomic -> (key.atom as? Atom.Ref)?.let { targets.add(it.id) }
                    is KeyValue.Record -> pending.addAll(key.fields.values)
                    is KeyValue.Variant -> key.payload?.let(pending::addLast)
                    is KeyValue.Sequence -> pending.addAll(key.items)
                    is KeyValue.MapEntries -> { pending.addAll(key.entries.keys); pending.addAll(key.entries.values) }
                }
            }
            obj.id to targets
        }
    },
)
