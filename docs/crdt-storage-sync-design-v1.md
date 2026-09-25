# CRDT, Storage & Synchronization Design — V1 Draft

**Date:** 23 September 2026  
**Status:** agreed architecture and initial technical mapping; wire and disk encodings remain open  
**Companion:** Core Engine Specification — V1

## 1. Boundary

One independently identified workspace has one composed CRDT root. Its durable journal is the authoritative record of committed changes. Typed documents, published type definitions, configuration, workflow definitions, run state and worker protocol records use this same mutation boundary. The permanently private root workspace uses the same implementation but has no synchronization connections.

YouTrackDB is a local graph projection, rebuilt from authoritative state. HTTP and an ordinary mounted directory are equal-priority synchronization adapters. Neither owns a second change format or a distinct document model. Browser and mobile replicas, workspace nesting and cross-workspace live references are outside V1.

This draft distinguishes approved behavior from implementation detail. It does not choose a serialization library, cryptographic algorithm, particular sequence CRDT or operating-system persistence API.

## 2. The small value and CRDT vocabulary

The structural families are registers, keyed collections and identified ordered sequences. Collaborative `Text` is a specialized sequence, not an atomic string. Tombstones and causal metadata are shared mechanisms used by those families. Algebraic and generic types compose them.

| Type expression | Value behavior | Conflict behavior |
| --- | --- | --- |
| Boolean, number, atomic `String`, enum | Typed register | Causally later assignment wins; concurrent assignments use a deterministic operation-ID order |
| `Ref<T>` and immutable-content reference | Typed register containing a stable ID/hash | Selection of the reference is atomic; referenced content is handled independently |
| Product/record | Fields with stable identities containing typed values | Edits to distinct fields merge independently |
| `Map<K,V>` | Keyed, identified entries with typed values | Different entries merge; operations on one entry use the value's type behavior |
| `List<T>` | Sequence of elements with stable internal IDs | Concurrent insertions survive and get deterministic order; element edits use `T` |
| `Text` | String CRDT over identified text positions | Visible concurrent edits compose |
| Tagged sum | Atomic selected tag plus payload-instance identity | Coherent variant selection; fields merge inside the same selected instance |
| `Optional<T>` | `None | Some<T>` | Uses the tagged-sum mechanism |

A typed document's published nominal identity is distinct from its structure. Transparent aliases name type expressions: they introduce no new nominal runtime identity or CRDT behavior. Alias definitions are immutable typed documents; a reference to an alias stays pinned to its exact definition version. An alias-only cycle without a real underlying type is invalid. Generic aliases resolve using the existing generic type rules.

An embedded value and `Ref<T>` differ in both meaning and edit behavior. A reference doesn't inline its target. Binary content is immutable and addressed by hash; a CRDT operation selects its content reference. Content chunks are stored and exchanged separately, then verified before use.

### Editing, replacement and deletion

An edit to a field targets a specific current value instance. Whole-value replacement creates a fresh internal instance and selects it atomically. A concurrent edit addressed to the replaced instance cannot leak into the selected replacement. A switch of union variant similarly selects a coherent tag/payload pair. The document/block's public ID need not change when its embedded value instance is replaced.

List elements have stable IDs independent of their positions. Deletion tombstones the identified element or object and wins against concurrent edits/moves to it. Restoration, if retained history permits, creates a new identity. Tombstones remain in V1.

The merge model preserves structural typing according to this mapping. It does not promise that arbitrary cross-field predicates stay true after independent valid edits merge. Local forms and engine-owned records validate according to their respective contracts; the type system must not claim global serializable business-rule validation.

## 3. Five logical mutation operations

The internal typed-change API compiles edits into these logical operations. Their precise byte-level CRDT operation variants may differ.

| Operation | Purpose |
| --- | --- |
| `Create` | Create an identified document, block, collection entry, list element or embedded value with a pinned type |
| `Assign` | Change an atomic slot, including scalar values, references, a selected value instance, or a sum variant |
| `Place` | Insert or move an identified item within an ordered list or containment structure |
| `Delete` | Tombstone an identified value, entry or element |
| `EditText` | Make position-aware insertions/deletions in collaborative text |

A whole-value replacement is `Create` plus `Assign` in one batch. `Place` preserves identity for moves inside one workspace. Moving across workspaces is a copy into the destination and a deletion in the source; it is not one atomic cross-workspace transaction. Containment-cycle and concurrent-move resolution must yield a deterministic valid tree, with the policy in the core spec; the exact algorithm is still open.

Ordinary editors and workflow nodes submit typed changes to one mutation API. The API validates types and allowed engine transitions and emits operations. Workflow definitions do not contain raw storage operations. A run's owner is its only authoritative state writer; assigned workers author separate immutable result records that the owner may consume. Since every batch is signed, a peer can verify the recorded author before applying these protocol rules.

## 4. Atomic causal batches

A batch is the smallest durable and visible commit. It can update several documents in one workspace. Its logical envelope contains:

- Workspace ID and protocol/encoding version.
- Emitter's connection public key, which is its author identity.
- Emitter's incremented counter and full causal vector.
- One ordered collection of typed operations, with pinned type IDs and value-instance IDs needed for validation.
- An integrity digest and mandatory author signature over a canonical representation of the logical batch.

Its identity is `(emitter public key, vector[emitter])` after incrementing the emitter's component. The vector captures the applied causal context, including observations from other emitters. Batch identity uses the author counter, while the full vector is carried as metadata. A relay preserves the original signed batch and may add transport-local publication metadata outside its signed logical content.

Each workspace replica has exactly one writer. Local edits, incoming ingestion and owner run commits are serialized through it. Concurrent callers may submit requests, but not mutate its state simultaneously. A second live process under the same replica identity is forbidden. Other distinct replicas remain free to make concurrent edits and rely on CRDT merge.

A local commit validates its proposed typed operations against the author's causal snapshot, persists the batch and incremented counter together, and only then reports success or exposes it to sync/projection. Incoming batches are verified and deduplicated, buffered until causal dependencies arrive, and applied atomically. Readers, projectors and workflow interpreters never observe a partial batch. A duplicate with identical identity/content is harmless; conflicting bytes for the same identity are a protocol fault, not a new concurrent edit.

A causal frontier describes complete applied prefixes per emitter. Pending out-of-order batches are held separately, and an incomplete frontier must not be advertised as though its holes were applied. Exact vector representation and reconciliation optimizations remain implementation work. No wall clock defines causal order or the register tie-break.

Unknown CRDT operations block the entire batch and its dependents, leaving the workspace read-only at the last supported state until upgrade. An unknown renderer for an otherwise supported block leaves synchronization possible through a generic view. Invalid signatures are never ingested as valid author operations; exact quarantine and diagnostic policies require a separate admission rule.

## 5. Local storage and recovery

V1 keeps a complete append-only batch journal for every workspace, including the private root. There is no history compaction or snapshot-based initial join. Local snapshots may eventually accelerate restart, but the journal is sufficient to reconstruct current CRDT state, tombstones and causal frontier. A new replica obtains and replays all available committed batches.

A durable local commit precedes asynchronous synchronization and graph projection. Restart discards or repairs any incomplete journal tail, rebuilds accepted state from complete valid batches, and republishes locally committed batches not yet observed elsewhere. Persisting the counter with its batch and restricting one live writer prevent identity reuse after a crash. The exact framing, checksum, flush and recovery sequence is an implementation decision that must prove these guarantees on supported platforms.

Immutable binary payloads use hash-verified chunks outside the batch journal. Batches carry references. A replica requests missing referenced chunks through its selected transport and shows pending content until complete. Consumers of a referenced blob wait; partial or hash-mismatched content is never supplied. V1 does no automatic binary garbage collection.

The local graph receives each fully resolved batch atomically and records a projection checkpoint. It can lag a durable commit, retry a failure, or rebuild from CRDT state. The write API returns a batch commit token; a local read may wait until its projection includes that token. Graph notifications never authorize workflow execution; the interpreter reads authoritative run state.

## 6. One sync protocol, two transports

A connection uses either HTTP or the mounted-directory transport; a client can use different transports for other replica connections. Both exchange the same signed logical batch bytes and immutable binary chunks, then call one verification, deduplication and causal-ingestion path. A peer may relay already accepted batches without rewriting their identities, clocks or signatures.

An HTTP sync session initiated by whichever peer can reach the other exchanges missing batches in both directions. A desktop with only outbound reachability to a headless server can both upload and download within its client-initiated session. V1 does not provide NAT traversal. Inventory/frontier exchange, chunk transfer, retransmission and session wire format remain to be specified.

For a mounted share, each publisher writes in its own workspace area. It may publish batches from other emitters while preserving the original signed content. Another publisher can independently publish the same logical batch; receivers deduplicate by original batch ID. This can duplicate bytes on the drive but requires no shared lock or permanent bridge peer.

Publication must make incomplete files distinguishable from ready candidates. A candidate is ingested only after its declared size/hash, framing and signature verify; content or a ready marker still being written is retried rather than partially applied. A crash may leave ignorable orphan temporary files. The exact publication method and filesystem durability assumptions require testing on ordinary mounted drives; V1 must not rely on sharing a mutable database file.

Because the complete journal is retained, a new peer can bootstrap by replaying the full history and fetching referenced blobs. This may take longer for large workspaces. Peer health, polling cadence and incremental inventory optimizations are local implementation details, not extra replicated channels for workflow execution.

## 7. Authorship, membership and bounded guarantees

Every batch is signed by its emitting connection key, and verification is mandatory on both transports before treating it as that author's work. The signature covers the workspace ID, version, counter, causal context and operations in one canonical representation; this prevents changing its claimed author or replaying it as a different workspace operation. The exact algorithm and canonical byte encoding remain open.

The accepted membership model is for small teams: connected participants have full workspace write access; any admitted peer can sign an admission record for a new connection or exclude another. There is no organisation-wide identity directory or central admission authority. Filesystem OS permissions are separate, and copies already distributed cannot be recalled. Signed authorship makes the owner and assigned worker checks enforceable as protocol rules. It does not create per-document ACLs, prevent an admitted peer from editing ordinary content, or guarantee a secure global cutoff under a partition.

**V1 admission rule:** Direct exchange is refused for an excluded peer or publisher. A logical batch is eligible when its signature verifies and its emitter identity was previously admitted to the workspace, even if that author is now excluded, provided an admitted peer relays the batch. A never-admitted author is not made valid merely by a relay. Replay does not apply an exclusion cutoff to earlier or later signed batches from a formerly admitted emitter. A signed admission record for a new connection is valid when its signer was admitted in the record's causal history and a revocation of that membership is absent from the record's causal context. An admission concurrent with the inviter's revocation remains valid; an admission causally after that revocation is invalid. This rule uses causal order and does not depend on which batch arrived first. This preserves complete-history reconstruction without arrival-order-dependent rollbacks. A cooperating admitted relay can carry new edits or owner-authored run records from an excluded peer; revocation is intentionally not a secure global cutoff. The application must describe this consequence clearly to users.

## 8. First verification slice

Two persistent JVM replicas edit typed content (including `Text`, a list and a union) and exchange signed batches over HTTP, then over an ordinary mounted directory. Include duplicates, delayed causal dependencies, disconnection, process restart, a relay and a malformed/partial file. Verify identical state after each replica has the same admissible batch set, durable counter recovery, and correct invalid/unknown-operation handling.

Rebuild YouTrackDB from the journal and wait on a write token. Then run one workflow with a remote pinned worker. Its request and result travel as signed typed records; only the original owner commits advancement. This validates that content and execution use the same primitive storage and sync mechanisms.
