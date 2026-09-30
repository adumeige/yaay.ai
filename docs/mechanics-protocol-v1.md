# Implemented mechanics protocol v1

This document fixes the choices left open in the design for this implementation.
The wire discriminator is version 1. It is an initial implementation format, not a
promise that later development revisions will accept every earlier prototype file.
The acceptance audit remains authoritative about tested environments and release gates.

## Identity and canonical batches

A connection identity is its Ed25519 X.509 public-key encoding, represented as
unpadded URL-safe Base64. Alternate encodings of the same key are rejected. Keys
are generated once per workspace store, persisted locally and reused after restart.
The founder key and workspace ID are pinned at initialization. No global identity
service or owner takeover is introduced.

Batch framing uses big-endian signed 32-bit lengths/counts and signed 64-bit counters.
A byte string is its 32-bit byte length followed by its bytes. A string is a byte
string containing strict UTF-8; unpaired UTF-16 surrogates and invalid UTF-8 are rejected.
Booleans encode as one byte, 0 or 1. Optional strings encode presence, then the string.
Map keys and sets sort using Kotlin String ordering (UTF-16 code-unit order), with
no Unicode normalization. Operations preserve their original sequence order.

Canonical logical batch fields, in order:

1. String `yaay.batch`.
2. 32-bit protocol version.
3. Workspace string and emitter public-key string.
4. 64-bit post-increment author counter.
5. 32-bit vector entry count; sorted pairs of author string and 64-bit counter.
6. 32-bit operation count; each operation is a 32-bit tag and a byte-string body.

The signed container holds the logical byte string, its lowercase SHA-256 hex string,
and the Ed25519 signature byte string. The signature covers the entire logical byte
string. Verification recomputes the digest, checks the canonical author key and verifies
the signature. Decoders reject trailing bytes, duplicate map keys, noncanonical ordering
or encoding, and a re-encoding that differs from the original bytes.

Frames are limited to 16 MiB, including the signed container. Decoded collection counts
are limited to 100,000. These are admission limits, not truncated/partial acceptance.
The logical identity is `(emitter key, author counter)`. Identical redelivery is harmless;
different signed content under the same identity is a protocol fault.

## Values and operations

Atomic value tags are one byte:

| Tag | Fields after tag |
| --- | --- |
| 1 Boolean | Boolean |
| 2 Number | IEEE-754 binary64; admitted values must be finite |
| 3 String | String |
| 4 Embedded selection | Instance-ID string |
| 5 Reference | Target-ID string |
| 6 Variant selection | Tag string, optional payload-instance-ID string |
| 7 Immutable content | SHA-256 hash string, 64-bit size, chunk count and ordered chunk-hash strings |

Operation tags and byte-string bodies:

| Tag | Operation | Body fields |
| --- | --- | --- |
| 1 | Create | ID, pinned type expression, 32-bit shape, field count and sorted field-name/atomic-value pairs |
| 2 | Assign | Target ID, field name, atomic value |
| 3 | Place | Target ID, optional parent ID, optional sibling anchor ID |
| 4 | Delete | Target ID |
| 5 | EditText | Target ID, optional text-position anchor, insertion string, deletion count and sorted position IDs |
| 6 | Membership record | Connection public key, admitted Boolean |

Shapes are REGISTER=0, RECORD=1, MAP=2, LIST=3, TEXT=4, SUM=5. Unknown operation tags
retain their opaque body; once signature, author eligibility and causal prerequisites
verify, the complete batch freezes the workspace at the last supported state. Its
operations and dependents are not partially applied. Malformed known operations are
rejected, not silently treated as successful unknown operations.

Operation IDs are `(batch identity, zero-based operation index)`. Created IDs use
`author:counter:index`; text scalar IDs add `/scalarIndex`. Registered field conflicts
choose causal maxima, then greatest operation ID (author, counter, index). No wall
clock participates. Text positions are Unicode scalars, not code units or graphemes.
Sibling insertion runs visit causal successors before concurrent operation-ID ties.
Traversal retains deleted anchors and uses an iterative successor walk.

Concurrent moves prefer the lexicographically smallest destination. Cyclic parent
choices discard the greatest movable operation ID in the detected cycle and try its
previous placement. Fixed embedded-owner edges participate in cycle detection and
cannot be independently moved. Embedded replacements require fresh instances; `Ref`
is the way to reference existing content. Tombstones are retained permanently.

Published types use immutable CRDT objects with bootstrap type
`yaay:type-definition:1`. The portable type grammar in `TypeEncoding` uses tagged,
length-prefixed strings with pinned nominal/alias IDs; aliases preserve structural
assignability and nominal definitions preserve identity. Type/key grammar lengths
count UTF-16 units and nesting is limited to 128. A definition object holds the
encoded definition in `value` and may carry an optional `name`: display metadata, a
non-reserved identifier, immutable with the definition and not part of its identity.
Several versions may share a name; references always pin the definition's object ID.
The human-readable declaration syntax (`TypeSyntax`) compiles to this encoding and is
not itself stored.

`Any` (tag `x`) is a slot type: it holds an embedded instance of any concrete type, which
is validated against the type it records. No object has type `Any`, and `Any` cannot be a
map key type. Built-in definitions are known to every peer without being published:
`yaay:builtin:folder:1` (`Folder = { title: String, children: List<Node> }`),
`yaay:builtin:document:1` (`Document = { title: String, content: Any }`) and
`yaay:builtin:node:1` (`Node = Folder(Folder) | Document(Document)`). Their IDs cannot
collide with published objects, whose IDs begin with a verified author key. The names
`Any`, `Node`, `Folder` and `Document` are reserved.

The workspace tree is this typed data. Live `Node`s without a parent form the top level;
a folder's `children` list holds its nodes; moving a node is a `Place`, so its identity
and references survive. Validation keeps the tree well-formed: a `Node` is top-level or
in a folder's children, a `Folder` or `Document` exists only as a node's payload, and a
`List<Node>` only as a folder's children. User definitions may name tree types only as a
direct `Ref` target, so documents never embed documents. A node is visible when it, its
payload and every ancestor are live: deleting a folder hides its subtree, while a node
concurrently moved out stays visible where its placement resolves. Typed map keys are immutable,
canonical finite values; existing string keys remain raw field names. The values
selected by keys are independent identified CRDT instances.

## Writer, validation and journal

Each durable store holds an OS lock for its lifetime. A synchronized writer serializes
local edits and incoming ingestion. Proposed operations validate against the author's
causal snapshot, including transitive vector closure and membership at that snapshot.
Invalid local proposals do not consume counters. Received invalid batches are rejected
and diagnosed; a delayed rejection does not turn a different successful durable commit
into an apparent failure. Pending causal holes do not advance the applied frontier.

A journal frame is `32-bit length | signed batch bytes | 32 raw SHA-256 checksum bytes`.
Appending forces the complete frame before success/state exposure. The author counter
is reconstructed from the same committed journal; it is not separately advanced.
Recovery verifies framing, checksum, canonical batch bytes, signature, causal context
and typed validity. An incomplete final frame is truncated and flushed. Complete-frame
checksum corruption fails closed without deleting valid prior history.

Identity installation writes and flushes a temporary local key file, atomically renames
it, then flushes the directory. POSIX temporary private-key files start with mode 0600.
A missing identity beside retained history is not replaced with new keys. The stored
public/private pair is checked on open. A full-history bootstrap is validated in an
isolated state machine, then atomically installs a separately flushed journal; process
crashes leave the original empty journal or the whole history, never half an admission
chain. Orphan temporary files are ignored. V1 retains all history and has no compaction.

## Common exchange and HTTP

A transport envelope contains a byte-string logical body and signature byte string.
Its body is `yaay.sync.1`, author key, workspace, purpose, nonce and payload byte string.
The five header strings use Java DataOutput `writeUTF`/`readUTF` (two-byte lengths and
modified UTF-8); payload framing uses the same 32-bit byte-string convention above.
Envelope size is bounded to 16 MiB + 8192 bytes. Canonical re-encoding and Ed25519
verification precede any command dispatch. Responses match the request nonce,
workspace and pinned remote key. These signed bytes are distinct from batch signing.

HTTP uses POST `/sync`, no query, content type `application/vnd.yaay.sync-v1`. The
embedded server returns 200 for completed requests, 403 for invalid/refused requests,
and 503 for other failures. Clients reject redirects, bound response bytes and impose
an overall response-body deadline. A client-initiated session performs both directions;
no callback listener or NAT traversal is required. The embedded listener is HTTP;
an HTTPS reverse proxy may supply confidentiality.

The payload starts with a `writeUTF` command:

| Command | Request fields | Response fields |
| --- | --- | --- |
| inventory | Exclusive last-seen hash string, initially empty | Count (0–128), sorted signed-batch SHA-256 hash strings |
| batch-get | Hash string | Original signed-batch byte string |
| batch-put | Original signed-batch byte string | Empty body; application may buffer causal dependencies |
| chunk-get | Hash string | Available Boolean, then chunk byte string if present |
| chunk-put | Hash string, chunk byte string | Accepted Boolean; unaccepted references defer the chunk |

Reconciliation repeats inventory after reconnection. Publication hashes identify bytes;
the original signed author/counter identifies logical batches. Relays never re-sign
logical content. New peers verify full history from the pinned founder and confirm
the contacted relay's membership before installing it. A stale inventory may delay
progress until the next session; it does not authorize partial batch application.

## Mounted-directory publication

The configured mount root is trusted local configuration. Descendants are
`<workspace SHA-256>/<publisher-key SHA-256>/`. Each candidate comprises
`batch-<hash>.payload` or `chunk-<hash>.payload` and the corresponding `.ready` file.
Ready content is a signed transport envelope with purpose `publication`, nonce
`<kind>-<hash>` and payload `(writeUTF kind, writeUTF hash, 32-bit payload size)`.

The publisher writes a temporary payload, flushes it, and publishes it before the
signed ready candidate. Receivers accept only complete framing, matching declared
size/hash, a valid publisher signature, admitted immediate publisher and valid original
batch signature/author history. Temporarily incomplete/corrupt ready or payload bytes
are diagnosed and retried. Temporary files are ignored. Re-publication is idempotent
and requires no cross-publisher lock or mutable shared database.

Every descendant open/read/create/delete/rename is relative to pinned
`SecureDirectoryStream` handles, with no symlink traversal. Replacing a publisher's
path while a file is being written cannot redirect it into another directory. Random
empty directory creation occurs directly under the trusted mount root, then moves
relative to pinned handles. A provider without secure directory handles is explicitly
refused; local commits remain available and durable. The current verified environments
are Linux/JDK 21 and macOS/JDK 25; the macOS provider lacks secure directory handles on
JDK 21. This does not claim Windows-provider or arbitrary share support.
Pinning holds on local filesystems and NFS, which resolve names against directory
handles. SMB2 opens by share-relative path, so over SMB a concurrent rename by another
machine is not prevented by client handles; there the guarantees rest on no
client-side link traversal, validated publication names, and signature/hash checks
on read.
The actual mounted-share release gate must establish the intended deployment provider.

Readiness does not rely on atomic mounted-drive rename or directory flush semantics.
Receivers may miss an in-progress publication and retry later. The authoritative local
journal remains the retransmission source. Missing access/unmount does not invalidate
local commits. Excluded publishers are refused; an admitted relay can still carry
signed batches from a previously admitted author, including after exclusion. Exclusion
is deliberately not a secure global cutoff, and cannot recall distributed copies.

## Immutable content

Chunks are at most 1 MiB and addressed by lowercase SHA-256. A content reference carries
its full hash, size and ordered chunk hashes. Chunk reception verifies bytes before
local storage. Consumers wait for complete content, verify total size/full hash, then
receive a stream; partial or mismatched bytes never escape. Corrupt local chunks are
missing for reconciliation and can be repaired from an intact peer. No automatic
binary garbage collection is implemented. `typedSyncEndpoint` additionally discovers
content references inside typed map-key literals.

## Verification limits

See `mechanics-acceptance-audit.md` for every R/T/B/A/X requirement and test. Process
termination is tested at local journal, identity, bootstrap and publication boundaries.
It does not simulate storage-device power loss. The separate-machine ordinary SMB/NFS
mounted-directory gate, including actual unmount/recovery, remains unrun; local
filesystem tests and multiple JVMs on one host do not establish it.
