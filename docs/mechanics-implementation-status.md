# CRDT, storage, transport and sync implementation status

The assignment covers the CRDT, typed mutation support, durable JVM storage, both
sync adapters and immutable content described in the design. The existing Gradle
KMP structure is preserved. No layered-build workflow was used.

## Current evidence

`./gradlew build` succeeds. The core/document suites contain **74 passing tests**:

| Suite | Tests | Main evidence |
| --- | ---: | --- |
| ReplicaTest | 15 | Causality, registers, replacements, sequences, membership, signatures, immutable API boundaries and seeded delivery |
| DurabilityTest | 10 | Separate-process locks, journal/bootstrap/identity crash boundaries, counter recovery, corruption, private-root policy |
| TransportTest | 11 | Shared adapter traces, relay bootstrap, invalid/unsupported batches, reverse dependencies, multi-page history, pending/interrupted blobs |
| HttpFaultTest | 4 | Concurrent sessions, stale inventory, truncated/delayed responses, durable retransmission |
| DirectoryFaultTest | 7 | Exclusion/relay, split membership, unavailable share, chunks, symlinks and directory replacement races |
| PublicationProcessTest | 1 | Process death before/after readiness and idempotent republication |
| TypesTest | 4 | Nominal identity, aliases, generics, references, immutable definitions and rejection |
| TypedEditTest | 14 | Nested/variant merges, map entries, tombstones, Unicode text, exact ordering, ownership cycles and deterministic structural schedules |
| TypedSyncTest | 1 | Persistent typed Text/list/sum through both adapters |
| MapKeyTest | 4 | Generic/algebraic keys, canonical identity and referenced content transfer |
| PeerProcessTest | 3 | Headless CLI peers, complete typed/tombstone replay and concurrent relay JVMs |

The requirement-by-requirement evidence is in
[mechanics-acceptance-audit.md](mechanics-acceptance-audit.md). Structural property
runs use seeds 0–7 with deterministic test keys and 45 commands per seed; scalar
schedule tests use seeds 0–11. Concrete examples assert exact values/order as well
as convergence. Process tests halt actual child JVMs at durability/publication
boundaries. They do not simulate storage-device power loss.

## Implemented contracts

- One composed root per workspace; single serialized writer and OS process lock.
- Mandatory canonical Ed25519-signed batches, SHA-256 integrity, author counters,
  complete causal frontiers, dependency buffering and duplicate/equivocation rules.
- Create/Assign/Place/Delete/EditText semantics, coherent embedded replacement,
  fixed embedded ownership, deterministic cycle/move resolution, tombstones and
  Unicode scalar text positions. No wall clock decides conflicts.
- Immutable nominal definitions, pinned transparent/generic aliases, recursive
  values, records/sums/optionals, identified lists/maps, typed references, enums,
  generic finite map keys, typed edit builder and renderer-independent views.
- Complete flushed journal, all-or-none bootstrap installation, recoverable tail,
  persisted key identity, fail-closed corruption and unsupported-operation state.
- One logical ingestion path behind two-way HTTP and ordinary directory publication,
  pinned remote signatures, relays, full-history join and reconnect reconciliation.
- Separate hash-verified immutable chunks, waiting consumers, repair from peers and
  no automatic binary garbage collection or history compaction.
- Direct exclusion distinct from historical author eligibility; admitted relays can
  carry a formerly admitted author's later signed changes. CLI output documents this.
- Detached input/output collections prevent mutation of already accepted history.

The implemented wire/disk/readiness rules and bounds are specified in
[mechanics-protocol-v1.md](mechanics-protocol-v1.md). Runnable commands and a two-peer
walkthrough are in [headless-peer.md](headless-peer.md). Typed JVM integrations use
`TypedMutationValidator` and `typedSyncEndpoint` so content referenced inside typed
map keys participates in the same chunk transfer path.

## Environment and remaining release gate

The verified environment is Linux with the JDK 21 Gradle toolchain. The directory
adapter requires filesystem-provider secure directory handles; it refuses providers
without them instead of falling back to path-racy I/O. This does not claim support
for Windows filesystem providers or arbitrary mounted-share semantics.

The required **ordinary mounted SMB/NFS share with separate processes on separate
machines has not been tested**. `findmnt -t nfs,nfs4,cifs` found no such mount here.
The user deferred this gate to later manual testing. It must cover actual share
interruption/unmount and recovery and record the intended environment.
Local temporary directories and multiple JVMs on one machine do not substitute for it.

The implementation goal is complete with this external verification explicitly
deferred by the user; full release acceptance is not claimed. YouTrackDB and workflow
execution are later milestones under the acceptance plan; this work provides their signed
mutation/commit-token foundation without introducing a separate messaging channel.
