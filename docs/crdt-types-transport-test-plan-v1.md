# CRDT, Type System & Transport Test Plan — V1

**Status:** implementation acceptance plan, 24 September 2026  
**Build:** Maven; Kotlin/JVM core with platform boundaries  
**Companions:** Core Engine Specification — V1; CRDT, Storage & Synchronization Design — V1 Draft; Codex Implementation Plan — V1

## 1. What this plan establishes

The core must converge when distinct replicas ingest the same admissible signed batches, regardless of delivery order and duplicates. Typed values must remain structurally coherent under the specified merge rules. A locally durable commit must survive restart. HTTP and a mounted directory must exchange the same logical batches, with identical outcomes after full delivery.

Tests distinguish three things: whether a proposed edit is valid in the author's snapshot, whether a signed batch is admissible, and how concurrent admissible changes resolve. A valid local form can participate in a merged value that violates an arbitrary cross-field business rule; tests must not claim otherwise. Likewise, peers disconnected by membership exclusion are not required to converge unless they eventually receive the same admissible set.

The test suite grows by milestone. M0a/M0b use an in-memory deterministic scheduler. M1 uses real local durable storage with process restarts. M2 reruns shared scenarios over actual HTTP and ordinary mounted directories. Protocol and format stability are not asserted until those encodings have been specified.

## 2. Test harness and evidence

A scenario starts from an explicit workspace genesis, type definitions, connection identities and signed admission records. It describes client commands, each author's observed causal frontier, generated batches, a delivery schedule and the expected observable state. The harness must capture the entire signed batch history and the delivery schedule so any failure can be replayed by seed or fixture.

Use two related oracles:

1. **Concrete examples:** state exact expected values, identities, active sum variant, tombstones and ordering. These catch a consistently wrong result that would still converge everywhere.
2. **Metamorphic convergence:** generate a well-typed causal batch DAG, then deliver the same admissible set in several topological orders with duplicates and delays. Canonical projections of the resolved state, tombstones and frontier must match. Compare independently reconstructed replicas, never only two views of the same object.

Do not generate impossible batches as though they were legitimate author output. Keep a separate malformed/adversarial corpus for invalid signatures, invalid type IDs, broken references and conflicting batch bytes. State exactly whether each case is buffered, ignored, rejected as a protocol fault, or freezes the workspace under the core spec.

Property runs should use reproducible seeds; a failure prints a compact list of authors, operations, causal vectors and delivery order. Minimize a failing history where practical. Preserve each discovered bug as a small regression fixture. This is meaningful state-machine testing, not a test for every method or serialized data class.

### Schedule controls

The simulator can author at two or three distinct replica identities; deliver, duplicate or delay arbitrary batches; withhold dependencies; restart a replica from a supplied durable abstraction; relay the same signed bytes through a different publisher; and drain until each selected replica has the same admissible set. It never runs two live writers under one replica identity.

For M1/M2 the same scenario vocabulary drives process and transport adapters rather than reimplementing merge logic in the test harness. Use a bounded timeout only to detect lack of eventual progress after connectivity is restored; do not assert exact wall-clock timing or global real-time ordering.

## 3. CRDT structure matrix

| ID | Scenario | Required observable result | Milestone |
| --- | --- | --- | --- |
| R01 | Concurrent assignments to one atomic scalar, both orders | Same winner under deterministic operation-ID order; no wall-clock dependence | M0a |
| R02 | Causally later scalar assignment | Later value wins regardless of delivery schedule | M0a |
| R03 | Concurrent edits of different fields of one product instance | Both fields retained | M0a |
| R04 | Two assignments to the same nested field | Its typed register rule applies; other fields unaffected | M0a |
| R05 | Replace embedded instance while editing its old child concurrently | Replacement selected; old child edit cannot leak into it | M0a |
| R06 | Duplicate delivery of batch containing several operations | Entire batch visible once; no duplicate created values | M0a |
| R07 | Batch arrives before prerequisite type/value/parent | It waits; no partial object, orphan or prematurely advanced frontier | M0a |
| R08 | Tagged variant switch concurrent with edits to old payload | Coherent selected tag and payload; old edits excluded from active variant | M0b |
| R09 | Concurrent edits to different fields of same active variant instance | Both retained | M0b |
| R10 | Concurrent insertion of two elements at same list anchor | Both present, stable identities, deterministic order | M0b |
| R11 | Move a list element concurrently with its deletion | Tombstone wins; no revived element | M0b |
| R12 | Two moves of same item to different parents | One deterministic placement using specified tie-break, stable ID | M0b |
| R13 | Concurrent moves would form containment cycle | Valid deterministic acyclic tree; rejected placement leaves valid prior placement | M0b |
| R14 | Delete versus edit of document, block, map entry or list element | Deletion wins; historical edits do not appear as live content | M0b |
| R15 | Recreate after observed deletion | Fresh identity; references to deleted identity remain broken | M0b |
| R16 | Concurrent insertions/deletions in `Text`, including Unicode and line breaks | Both editors' surviving contributions appear in deterministic order; no malformed text | M0b |
| R17 | Same concurrent operations on atomic `String` | Whole-value register winner rather than merged text | M0b |
| R18 | Different map keys edited concurrently, same key edited concurrently | Independent keys retained; same-key value follows its own type behavior | M0b |
| R19 | Deeply nested product/list/sum with replacements at different depths | Each surviving value has one coherent selected instance and type | M0b |
| R20 | Long loop of random valid commands across three replicas | Same canonical state after every peer ingests the same admissible set; seeds replay | M0b |

The text CRDT's exact editing unit and cursor behavior need implementation definition before writing an oracle for every Unicode boundary. The V1 gate requires valid text and convergent visible edits; it must not silently equate UTF-16 offsets, code points and grapheme clusters.

## 4. Type-system and validation matrix

| ID | Scenario | Required result | Milestone |
| --- | --- | --- | --- |
| T01 | Two distinct nominal definitions have identical fields | They are not implicitly assignable to one another | M0a |
| T02 | Transparent alias names a composed type | Alias and underlying expression are assignable without conversion or a new nominal runtime identity | M0a |
| T03 | Publish new alias version with changed target | Old references keep their exact version and resolution | M0a |
| T04 | Publish new nominal type version, including same structure | Old instances retain old identity; no automatic migration | M0a |
| T05 | Submit invalid scalar, wrong field type, missing required field or invalid reference type | No valid batch is committed; staged edit stays local | M0a |
| T06 | Submit whole-value replacement | New instance gets an identity and valid complete structure in one atomic batch | M0a |
| T07 | Submit mixed valid and invalid operations in one batch | None becomes visible | M0a |
| T08 | Attempt to mutate published type definition | Rejected; new definition version is the supported path | M0a |
| T09 | Switch a tagged sum | Tag and matching payload selected together; mixed alternatives never appear | M0b |
| T10 | Generic alias and generic nominal type applied to concrete types | Substitutions preserve nominal identities and field validation | M0b |
| T11 | Valid recursive nominal type | Finite values and references validate without infinite type expansion | M0b |
| T12 | Alias-only cycle such as A = B, B = A | Rejected with a bounded error, not infinite recursion | M0b |
| T13 | `Ref<T>` versus embedded `T` | Explicitly distinct, with no silent dereference or cast | M0b |
| T14 | `Optional<T>` represented by tagged alternatives | `None` and `Some` transitions are coherent | M0b |
| T15 | Valid independent edits merge into a business-invalid cross-field combination | Engine preserves specified CRDT result; it makes no false cross-field guarantee | M0b |
| T16 | Unknown specialized renderer with supported underlying type/operations | Value remains available through generic representation | M2 |
| T17 | Unknown CRDT operation in an otherwise valid batch | Whole batch and dependents blocked; workspace read-only at last supported state | M2 |

Type tests should validate through the public typed-edit boundary and through incoming signed batches. These are different paths: a local invalid proposal never enters the journal, whereas an unsupported remote operation is handled according to compatibility rules. Do not silently discard one field of a malformed batch and apply the rest.

## 5. Signed batches, causality and journal

| ID | Scenario | Required result | Milestone |
| --- | --- | --- | --- |
| B01 | Tamper with workspace ID, emitter, counter, vector or operation bytes | Signature/integrity verification fails before ingestion | M0a |
| B02 | Valid signed batch replayed unchanged | One logical application | M0a |
| B03 | Same emitter/counter, different signed content | Protocol fault; never treat as two concurrent edits | M0a |
| B04 | Batch for another workspace | Never applied to this workspace | M0a |
| B05 | Three-emitter causal chain delivered backward | Dependencies buffer; complete ordered application after missing history arrives | M0a |
| B06 | Counter and batch persisted, then restart before sync | Same batch retransmitted; no counter reuse | M1 |
| B07 | Crash before local durability acknowledgement | On recovery, either full prior commit exists or no commit was acknowledged; no torn visible state | M1 |
| B08 | Crash during several-object batch or at journal tail | Entire batch recovered or ignored; no partial object | M1 |
| B09 | Concurrent request callers on one replica | One serialized commit order and unique increasing local counter | M1 |
| B10 | A second process opens same replica storage/identity | Rejected without two live writers | M1 |
| B11 | Replay complete journal from genesis | Current state, tombstones, type identity and applied causal frontier match pre-restart | M1 |
| B12 | History available through relay after original author disappears | New replica can reconstruct by full replay | M2 |

Mandatory signing proves the emitter key, not the human at a keyboard. Admission and direct exclusion remain the small-team protocol rules in the design; do not add a role hierarchy to satisfy these tests.

## 6. Membership scenarios

| ID | Scenario | Required result | Milestone |
| --- | --- | --- | --- |
| A01 | Admitted A signs admission of C; C's first batch depends on admission | Valid after causal admission has arrived | M0b |
| A02 | Never-admitted key authors a validly signed batch; admitted peer relays it | Rejected as an unknown author | M0b |
| A03 | A admits C concurrent with B revoking A | C remains admitted independent of delivery order | M0b |
| A04 | A's admission batch causally includes revocation of A | Admission invalid | M0b |
| A05 | Excluded A tries direct HTTP sync or publishes under its own shared-folder area | Direct exchange excluded at observer | M2 |
| A06 | Admitted B relays valid signed batch from formerly admitted A | Batch eligible; exclusion is not a global cutoff | M2 |
| A07 | Disconnected peers mutually revoke each other | Split is allowed; no invented central reconciliation | M2 |

Admission test fixtures must include signed membership records and causal context. A receiver must not decide A03 using arrival order. Convergence comparisons apply to the same admissible change set; a split where peers deliberately do not exchange the same set is outside that premise.

## 7. Transport conformance

Implement one reusable scenario suite against an exchange interface. For each logical trace, run it with the in-memory transport, HTTP and a mounted-directory adapter, and compare the accepted batch IDs, normalized CRDT state and referenced binary availability after all expected deliveries complete. Transport-specific files, retries and session counts may differ.

| ID | Shared scenario | HTTP evidence | Mounted-directory evidence |
| --- | --- | --- | --- |
| X01 | Two peers exchange concurrent signed edits in both directions | One initiated session uploads and downloads missing batches | Both peers publish and ingest their own areas |
| X02 | One-way reachability to headless server | Desktop-initiated session carries both directions; no server-to-desktop inbound connection | Not applicable; both peers see mounted share |
| X03 | Disconnect midway, reconnect, repeat inventory | Missing batches eventually fetched once logically | Polling catches newly ready files; duplicates harmless |
| X04 | Delayed dependency and out-of-order arrival | Buffer until prerequisite is transferred | Same buffering through common ingestion path |
| X05 | Relay with original emitter gone | Relay serves original signed bytes | Relay publishes in its own area without rewriting author |
| X06 | Two relays publish same batch | Duplicate transfers dedup by original ID | Duplicate files in two publisher areas dedup by original ID |
| X07 | New replica joins after long history | Full journal replay and referenced chunks | Full published history replay and referenced chunks |
| X08 | Unsupported operation or invalid signature | Same compatibility/admission result as core | Same result as core; no partial application |
| X09 | Large immutable binary referenced before transfer completes | Pending content and waiting consumer; verify full hash | Same pending and hash behavior |

### Filesystem fault cases

Run a fast local-directory suite during development, then run the release gate on an **ordinary mounted network directory with separate processes on separate machines**. Exercise the actual share type planned for first use, such as SMB or NFS, without assuming that a local temp directory proves the same behavior.

- Kill a publisher before writing its ready indication; leave an orphan partial payload. No reader applies it.
- Make the ready indication visible while payload bytes are temporarily missing, incomplete or delayed. Readers retry validation and never publish partial state.
- Truncate or alter payload and ready metadata. Reject the candidate; preserve the last valid workspace state.
- Restart a writer after publication but before it records local sync bookkeeping. Re-publication or rediscovery remains idempotent.
- Publish through two relays concurrently in separate publisher areas. Neither needs a cross-peer lock.
- Deny access to or temporarily unmount the share, then restore it. Local commits remain durable and later synchronize.
- Keep file names/paths untrusted until canonical validation; a malformed publisher path or file must not become a write outside the workspace store.

The publication protocol must specify its own readiness and retry rules before these tests can be implemented. Avoid asserting that an arbitrary mounted filesystem provides stronger rename or flush guarantees than demonstrated by the chosen implementation and test environment.

### HTTP fault cases

- Initiator drops mid-session; next session transfers the missing direction without committing partial logical batches.
- Both peers initiate sessions concurrently; duplicate delivery stays harmless.
- Endpoint answers with a stale inventory, delays a body or repeats content. Subsequent reconciliation still reaches the same state.
- One side is reachable only as an HTTP client; a session it opens must upload and download.
- An excluded direct peer is refused, while an admitted relay can carry a formerly admitted author's signed history.

## 8. Gates, reporting and scope

**M0a gate:** Concrete R01–R07, T01–T08 and B01–B05 pass. Seeded schedules covering duplicate and causal delay converge. The public typed-edit API exposes complete batches; no filesystem or network mock is mistaken for M2.

**M0b gate:** R08–R20, T09–T15 and A01–A04 pass; randomized interleavings are reproducible. If the exact move-cycle or Unicode cursor rule is still undefined, choose and document it before claiming this gate.

**M1 gate:** B06–B11 pass using actual restarted processes and local storage, including interruption at relevant durability boundaries. A test that only constructs a new object in one process does not establish crash recovery.

**M2 gate:** X01–X09 and A05–A07 pass for HTTP and mounted filesystem where applicable. At least one separate-machine mounted-share run records the environment and results. A new peer can replay the retained full history and obtain referenced binary chunks.

At each gate, report Maven commands, the test class/scenario IDs, seed(s) for randomized runs, observed state differences for failures, environment for mounted-share tests and known limitations. Do not turn M0's simulator into a second production interpreter. Workflow and graph tests belong to later implementation milestones; this plan tests the structures and transports they consume.
