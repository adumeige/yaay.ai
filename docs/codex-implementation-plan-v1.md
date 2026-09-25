# Agentic Workspace — Codex Implementation Plan

**Status:** implementation handoff, 23 September 2026  
**Authority:** read the current *Core Engine Specification — V1* and *CRDT, Storage & Synchronization Design — V1 Draft* alongside this plan. Resolve disagreement in favor of those specifications; report material gaps instead of inventing behavior.  
**Target:** desktop JVM application and headless JVM peer, with common Kotlin logic. HTTP and an ordinary mounted-directory transport are equally required in V1.

## 1. Strategy

Build the three foundations in slices: typed documents/blocks, the workspace CRDT, and a single-owner execution interpreter. Put code where it proves a required invariant. Start with an in-memory two-replica simulator; introduce disk, network, graph projection and UI only after the core semantics survive concurrent histories and replay.

A new repository can begin as a Maven reactor with `core` and `runtime-jvm` modules. `core` is Kotlin/JVM in V1 but keeps its domain and CRDT logic free of JVM-only APIs through explicit platform ports. `runtime-jvm` owns disk, crypto, network and database adapters. An existing repository should adapt these boundaries to its conventions. This is a source-level portability discipline, not a claim that Maven is compiling Kotlin Multiplatform `commonMain`; actual non-JVM targets and their build setup are later decisions. Keep build structure small; add modules only when a real dependency boundary appears. No broker, organisation directory, generic plugin system, cross-workspace transactions or browser/mobile runtime is needed for the first implementation.

Use executable examples and fault tests as acceptance gates. A milestone is complete when its observed behavior survives restart/reordering where relevant, not merely when its interfaces compile. Avoid tests that restate the implementation without exercising a convergence or recovery risk.

## 2. Milestone sequence

| Milestone | Work | Completion evidence |
| --- | --- | --- |
| M0a — core semantics | Typed identities, bootstrap type declarations, transparent aliases, `Create`/`Assign`/`Delete`, signed causal batches, one writer per replica, in-memory integration | Two replicas applying the same valid batches in different orders converge; duplicate batches do nothing; atomic replacement excludes edits to the replaced instance; invalid/unknown operations behave as specified |
| M0b — collaborative structures | `Place`, identified lists/containers, `EditText`, tagged sums, maps, deterministic moves/cycles/tombstones, recursive and generic type composition | Concurrent insertion, move, deletion and text editing cases converge under reordered delivery; structural type identities remain coherent |
| M1 — durable replica | Append-only local journal, crash-safe counter/batch persistence, process exclusivity, private root using the same machinery, recovery of complete batches | Crash/restart at commit boundaries never reuses an ID or exposes a partial batch; replay reconstructs authoritative state including tombstones and frontier |
| M2 — sync and blobs | One batch-exchange API; HTTP two-way sessions and mounted-directory publication; immutable hash-verified binary chunks; relay/dedup; complete-history join | Same two-peer scenarios work over each transport; relay after emitter loss, duplicate publication, delayed dependencies, partial files and new-replica join are exercised |
| M3 — graph read model | YouTrackDB adapter behind an application graph SPI; atomic batch projection and checkpoint; rebuild; token-aware reads and complete-snapshot subscriptions | A read can await its commit; interrupted projection resumes; rebuilding the graph changes neither authoritative state nor workflow execution |
| M4 — execution interpreter | Frozen definitions and allocations; owner-only run transitions; requests/results through CRDT; depth-first list, loops, joins/ACK, failure/UNCERTAIN, sub-workflows | One local and one remote worker alternate; offline owner/worker blocks correctly; replay and duplicate results never double-commit; ambiguous effects require owner action |
| M5 — application surface | Browsable typed documents, generated forms and views, workflow editing, document-based Send to AI, response/review controls, headless peer packaging | A user creates content and a workflow, starts an agent, reviews a block, and finds run/output through ordinary document navigation |

M0a and M0b are one core proof split into small reviewable changes. The exact UI polish and additional node families follow after the three foundations have passed integration tests.

## 3. Core invariants to keep visible during implementation

- One CRDT root per workspace; the permanently private root uses the same commit/storage engine with sync disabled.
- Every logical batch has a workspace ID, emitter identity, author counter, causal context, atomic typed operations and a mandatory signature. Its ID is the emitter key plus its post-increment component of the vector clock. Relays preserve the signed batch.
- One live writer per workspace replica serializes local commits and incoming merges. Different replicas may edit concurrently.
- The five logical mutation operations are `Create`, `Assign`, `Place`, `Delete` and `EditText`. Whole-value replacement selects a fresh instance in one batch. Deletion wins against concurrent edits/moves. `Text` and atomic `String` have distinct behavior.
- Published nominal type definitions are immutable. Transparent aliases name type expressions without creating a nominal identity. Contracts and instances retain exact version references.
- Admitted peers can add peers and directly exclude peers. Exclusion is deliberately weak: an admitted relay can still propagate valid signed batches from a formerly admitted emitter. Concurrent admission by an inviter survives the inviter's concurrent revocation.
- Full journal/history is retained in V1. New replicas replay history. Binary chunks are separate immutable content; local snapshots and compaction are later work.
- Run owner is the exact starting replica. Every execution target is fixed at creation. Only the owner commits run advancement and consumes worker results. There is no reassignment, takeover, cross-run capacity policy or breadth-first traversal in V1.
- A worker never decides routing. It returns a staged proposal; the owner validates, evaluates edges and atomically commits state plus next work. Ambiguous side effects enter `UNCERTAIN` and wait for owner action.

## 4. M0 simulator design

Use a deterministic simulator with two or three named replica identities and a controllable delivery queue. A test can deliver any available signed batch, duplicate it, delay a dependency, stop a replica, restart its in-memory process around a supplied durable state abstraction, or have peers author concurrent batches.

Record the canonical observable state, including identity, selected union variant, field values, list/text order, deletion and membership, after each replica has applied the same admissible batch set. Assert equivalence despite different delivery orders. Preserve reproducible seeds and print a minimal failing history. Test examples should include:

1. Different fields edited concurrently and one scalar field assigned concurrently.
2. A value instance replaced while another replica edits its old nested field.
3. A union variant switched while the old payload is edited.
4. Two list insertions at one position; delete versus edit/move; concurrent moves that would form a containment cycle.
5. Concurrent collaborative text insertion and deletion.
6. Duplicate, delayed and out-of-order batches; dependency buffering and unknown operation handling.
7. Admission concurrent with revocation; direct exclusion and relayed previously admitted authorship.
8. Two different signed payloads claiming one author/counter: reject as a protocol fault.

The simulator exercises semantics; it is not a second runtime interpreter. It should call the same CRDT and type code used by the JVM runtime, with controllable storage/delivery ports.

## 5. First Codex assignment

Paste this into Codex in the actual repository. If no repository exists, choose its path and initialize it there first. Do not run the prompt against this planning-artifact directory as though it were the application repository.

> Read `core-engine-specification-v1.md` and `crdt-storage-sync-design-v1.md`. Implement **M0a only** from `codex-implementation-plan-v1.md` in Kotlin/JVM with source-level portable core design and JVM tests. If this repository is empty, initialize the smallest Maven reactor with a Kotlin/JVM `core` module and JVM tests; keep dependency versions explicit after checking their current compatibility. Do not add Gradle. Keep core domain logic independent of JVM-only APIs behind platform ports. Build a minimal typed value model with nominal type identity and transparent alias resolution, identified value instances, and `Create`/`Assign`/`Delete` logical mutations. Model complete atomic batches with emitter key, durable-style counter, causal vector and signature verification through a clear crypto boundary; use an actual JVM signature implementation in tests. Enforce one serialized writer per replica in the in-memory runtime. Provide a deterministic two-replica delivery harness covering reordered batches, missing dependencies, duplicate delivery, concurrent scalar assignment, editing separate record fields, replacing an embedded value while another peer edits the old instance, and rejecting two payloads with the same batch ID. Do not implement filesystem, HTTP, YouTrackDB, workflow scheduling, list/text structures or UI in this change. Run the tests, report the exact commands/results, and identify any specification ambiguity you encountered. Keep operation encoding replaceable; do not claim wire compatibility yet.

The first PR/review should expose its state model and conflict rules clearly enough to inspect. Avoid building interfaces for all future product features before this slice works.

## 6. Integration and review cadence

Review M0a's type/identity semantics before starting M0b. Review M0b's convergence scenarios before choosing disk encoding. Review M1's crash boundary before either transport. M2 must validate HTTP and filesystem with the same logical test histories. Start M3 only once batches and reads have unambiguous identities/checkpoints. M4 may use the authoritative state directly and must not wait for a graph projection event to execute.

A review should answer three questions: which invariant was added; what test or demonstration establishes it; which limitation remains. If implementation uncovers a contradiction, update the design documents deliberately and revisit the smallest affected slice. Keep this handoff current as those decisions become concrete.

## 7. Initial repository inputs

The implementation needs a repository or agreed new directory, a JDK/Maven toolchain, and copies of these three documents in the repository (for example under `docs/`). No external services or database server are needed for M0; everything runs locally. Docker, mounted directory access and YouTrackDB are introduced only for later relevant milestones.
