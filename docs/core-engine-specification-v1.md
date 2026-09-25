# Core Engine Specification — V1

**Status:** consolidated V1 design; primitive model and execution protocol outline  
**Date:** 22 September 2026  
**Scope:** workspace, content, type, execution, and agent semantics  
**Implementation target:** common Kotlin core; desktop JVM and headless JVM server peers in V1  
**Companion document:** CRDT, Storage & Synchronization Design — initial V1 draft

## 1. Purpose and interpretation

The product is a local-first application for small teams collaborating on typed content and distributed workflow execution. It does not target organisation-scale identity administration or centrally managed membership. Agents are workflows. Documents, reusable nodes, workflows, and runs share the document/block/type foundation rather than forming separate application silos.

Each client maintains a permanently private root workspace and can connect to independent workspaces. Shared state converges through CRDT synchronization. Each run has one coordinating owner replica and fixed execution targets. The owner dispatches one invocation, consumes its result, and commits the next run state. CRDT synchronization carries requests and results. There is no mandatory application server or broker; the owner is an explicit availability dependency for its own runs.

This specification records the latest agreed decisions. It deliberately replaces earlier proposals where the discussion changed direction. “Must” identifies a required behavior; “may” identifies a supported option. Illustrative field names and status labels are not frozen wire formats.

The execution model is deliberately small: one owner, fixed workers, one outstanding invocation per run, and depth-first ordering. Record shapes below make these decisions concrete without freezing wire encodings. Remaining technical choices are listed in §16. The primitive consolidation in §2 is an architectural proposal derived from the agreed behavior; it introduces no additional product features.

### 1.1 V1 boundary

| Included | Deferred or excluded |
| --- | --- |
| Common Kotlin core; desktop JVM application and headless JVM server peer | Browser replicas, Android, iOS, and native/JavaScript CRDT bindings |
| In-house CRDT core; independent workspace replicas | Nested workspaces, mounts, and cross-workspace execution or references |
| HTTP and mounted shared-directory synchronization, with equal priority | NAT traversal, managed relay services, and invitation UX refinements |
| Typed documents, blocks, references, containers, folders, and templates | Third-party block/editor plugins and a custom form/renderer builder |
| Nominal algebraic types, generics, recursive types, explicit conversion | Inheritance and automatic migration of values |
| Single-owner runs with fixed remote workers, depth-first traversal, loops, joins and sub-workflows | Reassignment, owner takeover, breadth-first traversal, per-node cross-run parallelism controls, and additional trigger families |
| Document-based AI commands, tool use, and owner-controlled per-block review | Multiplayer run controls and a dedicated chat/agent application surface |
| YouTrackDB graph projection behind an SPI | A second authoritative database or a required vector-search engine |
| Simple full-write membership and peer exclusion | Fine-grained grants, read-only membership, delegation policy, or precise revocation |
| Retained deletion information and recoverable retained history | Automatic binary garbage collection and a completed compaction design |

### 1.2 Scope of the companion technical document

This document specifies observable behavior and the guarantees required from the infrastructure. It does not choose serialization formats, journal layouts, causal-vector encodings, cryptographic algorithms, network endpoints, file layouts, or platform storage libraries. Those belong in the companion design.

HTTP and filesystem support are equally required; neither is an optional follow-up. The first filesystem deployment uses ordinary OS-mounted directories on JVM targets. A headless server is an ordinary peer and can relay only or advertise execution capabilities; a browser UI can be added later.

## 2. Minimal primitive model

### 2.1 Three foundations

The application is composed from three foundations. Types belong to the content foundation, but their interpretation remains real core functionality; storing a definition as a document does not eliminate the need for a type checker.

| Foundation | Irreducible responsibility | Derived application concepts |
| --- | --- | --- |
| Typed documents and blocks | Stable identity, typed values, containment and references; shared validation and interpretation of immutable type definitions | Documents, type definitions, templates, node/workflow definitions, forms, configurations, proposals and AI responses |
| Workspace CRDT | Durable atomic mutation batches, deterministic merge, deletion information and synchronization of one independent workspace | Collaborative editing, replicated configuration, execution requests/results and durable run records |
| Execution engine | Interpret a frozen workflow, stage changes, invoke capabilities, validate outcomes and advance one owner-controlled run | Agents, tools, transforms, human-input waits, review/application and sub-workflow calls |

A document is a browsable block. A type definition is an immutable typed document. A workflow is a typed document describing execution. A run is an engine-managed typed document. These distinctions select contracts and behavior, not separate storage systems.

### 2.2 Small data and behavior kernel

The content kernel needs scalar values, named type references, records/products, tagged alternatives/sums, collections, stable object references, and containment. Collaborative text is a distinct value behavior. Generics and recursive definitions compose those facilities. Binary content uses immutable content references (§4.5).

A small built-in type vocabulary defines how type-definition documents are read and validated. It bootstraps the system rather than depending on an infinite chain of user-created meta-types. Application types use the same representation. Publishing a user type adds data; adding a genuinely new storage or execution behavior requires implementation support. A document cannot introduce an arbitrary CRDT operator or executable implementation merely by declaring a type.

The block envelope supplies identity, type identity and shared presentation metadata. Type-specific content supplies its value. Containers and reference blocks use the common containment/reference semantics. No universal user-maintained name field is required.

The engine recognizes supported node behaviors through versioned execution contracts. V1 has built-in implementations, reusable compositions and host capability adapters; arbitrary executable code embedded in documents and third-party plugins are deferred.

### 2.3 Composition map

| Feature | Representation and behavior |
| --- | --- |
| Ordinary content | Typed blocks with editors/renderers; a document supplies browsing placement |
| Type | Immutable definition document interpreted by the shared type system |
| Template | Document content copied to create new instances; no separate type mechanism |
| Workflow and reusable node | Definition documents, contracts and references |
| Agent | Workflow with model/tool capabilities and explicit continuation points |
| Tool | Built-in operation or reusable node exposed through a typed contract |
| Run | Owner-managed document containing frozen definitions, fixed allocation, work list and committed scratchpad |
| Worker request and result | Engine-managed typed records in the same workspace; synchronized through ordinary CRDT batches |
| Human form | Renderer for a typed input plus an owner-controlled execution wait |
| AI response and proposal | Typed blocks linked to the run; acceptance uses the existing execution and commit boundary |
| Settings and capability advertisements | Typed documents; secrets and private settings live in the private root |
| Search, navigation and graph queries | Read projections of content; no independent source of truth |
| Transport | Adapter exchanging CRDT batches; no separate workflow messaging system |

Protocol records can be blocks in an engine-owned document rather than one browsable document per message. That physical arrangement is an implementation choice. Their author rules and immutability are enforced by the engine, as are run-state rules.

### 2.4 What remains infrastructure

Identity/cryptography, durable storage, transport, the type interpreter, capability adapters and renderers still require code. The goal is a small set of composable application primitives, not the claim that every mechanism is reducible to an editable document.

YouTrackDB is the V1 graph read model behind an SPI. It is rebuilt from authoritative CRDT state. Neither it nor a future vector index owns application truth. UI actions and agent tools use the same content and execution services; they must not bypass them with direct graph-database writes.

### 2.5 Execution vocabulary

| Term | Meaning |
| --- | --- |
| Owner | The exact workspace connection/replica that starts the run; sole authoritative run writer |
| Placement | A configured use of a reusable node inside a workflow |
| Invocation | One occurrence of a placement, also called an activation; each loop visit creates a new one |
| Attempt | One identified execution attempt of an invocation; explicit retries get new attempt IDs |
| Worker | The fixed replica assigned to execute a placement |
| Capability | A supported implementation or host resource advertised by a replica |

Names do not replace identities. The ordered work list determines scheduling; invocation IDs identify work and do not encode scheduling policy.

## 3. Workspaces, replicas, and identity

### 3.1 Private root and independent workspaces

Every client has one private root workspace. Its private status cannot change. It holds local configuration, private keys, connection settings, and per-workspace presentation preferences.

V1 workspaces are flat and independent. A client can create or connect to multiple workspaces. An ordinary workspace can remain private or be shared at the user's choice; only the private root is permanently non-shareable. Folders organize content within a workspace without establishing sharing boundaries.

V1 does not provide nested workspaces, mount nodes, transitive sharing through a hierarchy, parent/child identity mappings, or cross-workspace references. Sub-workflows remain supported within a single workspace.

A shared workspace has an ID independent of its replicas and connection identities. Its original creator or first contacted peer is not a permanent synchronization bridge.

### 3.2 Connection identity

A fresh key pair is generated when establishing a workspace connection. It is persisted and reused across reconnects. The public key identifies the connection to other participants; the private key remains local in the private root.

There is no global client public identity spanning all workspaces. Independently instantiated replicas are not implicitly deduplicated into one identity or one local store. Reconnecting an existing connection reuses its identity.

Loss of the private key means loss of the identity unless its existing key material can be restored from a user-managed backup. No identity recovery authority is provided. Run ownership does not automatically transfer after key loss.

### 3.3 Membership and exclusion

An accepted workspace connection has full read/write access. V1 has no read-only membership, per-document ACL, delegated grant hierarchy, or separate workflow-execution grant. Any admitted participant can sign an admission record for a new connection or revoke another participant's direct workspace access.

Revocation excludes the affected peer from further direct synchronization. A signed batch from an identity that was previously admitted remains eligible when an admitted peer relays it, regardless of whether its author is now excluded. Previously accepted content is not rolled back, and copies already shared cannot be recalled. This deliberately weak rule permits later contributions from an excluded author to return through a cooperating relay; exclusion is not a secure global cutoff.

Two disconnected participants can revoke each other and create disconnected groups. This is accepted behavior, not a case requiring automatic reconciliation or a central authority.

These rules define intentionally coarse sharing. The transport checks the immediate peer or publisher for direct exclusion; historical author identity is checked for prior admission and a valid signature. A never-admitted author is not made valid by relaying through another peer. Admission is valid when its signer was admitted in its causal history and that revocation is absent from the record's causal context. An admission concurrent with the inviter's revocation survives; an admission causally after that revocation does not. These rules do not promise immediate or precise revocation across disconnected replicas. Filesystem permissions remain separate: excluding an application peer does not remove that peer's OS access to a shared directory.

Run ownership and execution assignments are protocol rules, not membership roles. Exclusion does not automatically cancel a run. An owner unable to receive required results cannot advance it; no other peer takes over. Workers may finish an already-issued request, and external effects are not recalled.

### 3.4 Settings and signatures

Workspace settings are shared state unless explicitly designated as private client preferences. Every V1 batch is signed by its emitting connection identity, including ordinary content, run commits and worker request/result records. Recipients verify the signature before ingestion. Relays preserve the original signed batch unchanged; a transport publisher is not the author.

This replaces the earlier optional-signing setting. It makes the owner-only run rule verifiable across HTTP and shared-directory transport. A signature establishes authorship by the corresponding connection key; admission and coarse membership exclusion are separate protocol decisions. The canonical signed representation and cryptographic algorithm belong in the companion technical design.

### 3.5 Detachment

Removing a connected workspace from a client's navigation detaches the local connection. It does not delete everyone else's workspace or cancel its runs. Pending assignments remain and wait for the same replica to reconnect. Detaching the owner stalls its runs; there is no reassignment or takeover.

## 4. Content and document model

### 4.1 Blocks and documents

Documents are blocks, and review, typing, identity, and references use the same foundation. Document placement has a structural restriction: documents cannot be embedded inside other documents. They are browsable through the workspace's document/folder hierarchy.

Ordinary container blocks can contain other blocks. V1 includes the block capabilities needed for Markdown-equivalent documents: text, headings, lists, quotations, code, tables, links, images, and separators, together with containers, references, and the views needed for nodes, workflows, runs, and AI responses.

Specialized editors and renderers are associated with block types. A type may have several. The user can switch views; the selection is stored in the document and therefore synchronized. It is not a private viewer preference.

User-defined typed content initially uses generated forms and renderers. A future builder can attach custom forms and renderers to types, retaining generated views as fallback. Plugins are outside v1.

### 4.2 References

A reference block displays an existing source without copying it. It has its own view selection, independent of the source's view selection. It is read-only even when the user can edit the source; navigation to the source enables editing there.

A reference can target an entire document because a document is a block. References resolve only within the current workspace in v1. Stable source IDs preserve references through renaming and identity-preserving moves.

Deleting the source leaves a broken-reference placeholder. The engine must distinguish known deletion from content not yet available locally. Restoring content from retained history creates a new object identity rather than reviving a tombstoned identity.

### 4.3 Moves and deletion

Within a workspace, identity-preserving moves retain the moved object's ID. Concurrent moves select one placement deterministically; the agreed tie-break is the lexicographically smallest destination ID. Causally later moves supersede earlier moves.

Containment cycles are forbidden. A concurrent set of moves that would produce a cycle must be resolved deterministically into a valid tree, discarding a conflicting move and retaining a valid prior placement. The complete resolution algorithm belongs in the technical design.

A cross-workspace content move, when offered, is a copy followed by deletion, not an identity-preserving reference operation or distributed transaction. The destination gets new IDs and references to deleted source identities break. This does not introduce cross-workspace workflow calls or live references. A generalized cross-workspace copy/remapping UX is not a v1 foundation requirement.

Deletion wins over concurrent edits and moves, for blocks and list elements. Recovery is limited to retained history. Deletion information is retained; history from initial creation is not an enduring guarantee once future compaction is introduced.

### 4.4 Typed editing and commits

Unfinished typed-form edits stay local and uncommitted. Only valid values enter shared document state. The form creator chooses automatic or explicit commit; explicit commit is the default. Automatic text commit is a supported v1 bonus.

Undo applies only to uncommitted changes, regardless of author. Reversing committed content requires a new edit. Committed automatic typing therefore has no special historical undo exemption.

V1 validation is limited to guarantees preserved by the chosen CRDT representations. It must not assume arbitrary cross-field constraints remain true after independent valid edits merge. Remote updates and local staged edits are integrated through the CRDT model rather than a separate document-conflict system.

Workflow graph validity is a separate executability check: an invalid workflow definition may remain a draft, but cannot be instantiated (§7).

### 4.5 Binary content

Binary payloads are immutable. Replacement creates or references a different payload; the block's reference changes. Identical content is deduplicated by content hash within each replica, not across separately stored replicas.

All referenced payloads replicate automatically, including unopened content. Large payloads synchronize in chunks through the same synchronization mechanism. A block can expose a content-pending state; a consuming node waits until required bytes are complete and verified.

V1 performs no automatic binary garbage collection. Removing the last live reference does not reclaim the payload. Future history compaction and reclamation require a separate design.

## 5. Shared type system

### 5.1 Type semantics

The same type system defines documents, reusable nodes, workflow inputs/outputs, tool contracts, and generated forms. Templates are creation shortcuts and do not substitute for type definitions. Transparent type aliases give a name to a composed type expression without introducing a new nominal identity; generic aliases follow the same rule. Alias definitions are immutable typed documents whose exact versions remain pinned by references. Alias-only cycles with no underlying type are invalid.

Required type-system features:

- Nominal named identities: equal structure does not make independently named types interchangeable.
- Product types and explicitly tagged sum types.
- Composition, without inheritance.
- Built-in and user-defined generics.
- Recursive type definitions.
- Embedded values distinct from typed references to identified objects.
- Collaborative `Text` distinct from atomic `String`.

For example, `Success { receipt: Text } | Failure { reason: Text }` carries an explicit active variant. A reference such as `Ref<Customer>` is not interchangeable with an embedded `Customer` value.

Conversion between distinct nominal types requires an explicit conversion node or operation. Future UI shortcuts may construct that conversion, but the engine must not infer structural casts silently.

JSON Schema was the initial contract choice; the final decision is the shared nominal algebraic type system. JSON Schema can serve as an external representation where a supported mapping exists. It must not erase nominal identity, reference semantics, or other distinctions it cannot fully represent. Its precise mapping is not specified here.

### 5.2 Immutability and versions

Published type definitions are immutable. Updating a type creates a new version with a distinct nominal identity. Existing values and node/workflow contracts retain their exact referenced identity.

There is no automatic instance migration or implicit compatibility between versions. User-facing migration helpers may be added later; the underlying model remains explicit. This supersedes the earlier idea of allowing edits until a type is instantiated or referenced.

### 5.3 Observable merge behavior

These are semantic requirements, not algorithm selections:

| Value/change | Required result |
| --- | --- |
| Concurrent scalar writes | Deterministic operation-ID tie-break; causally later writes take precedence |
| Collaborative `Text` | String-CRDT editing rather than whole-string overwrite |
| Atomic `String` | Scalar replacement semantics |
| Switching a sum variant | Select a coherent tag-and-payload pair, never mixed alternatives |
| Edits within the same variant instance | Independently merge different fields |
| Variant switch versus edits to the old payload | Switch wins; old edits survive only in retained history |
| Embedded-object replacement versus old-object field edits | Replacement wins; old-instance edits do not leak into the new value |
| List elements | Stable internal identity independent of current index |
| Concurrent insertions at the same position | Retain both, ordered deterministically by operation ID |
| Deletion versus editing/moving the same element | Deletion wins |

## 6. Engine state and infrastructure contract

### 6.1 Workspace mutation boundary

A workspace is one CRDT root composed of multiple CRDT values. The core is implemented in common Kotlin, with platform adapters for networking and persistence. It is not a wrapper over another platform's CRDT engine.

Each local workspace replica has one writer serializing local commits and incoming merges. Computations run outside the writer against snapshots and submit staged results for validation and commit.

A batch must be persisted locally and exposed atomically. Observers and workflow scheduling must never see part of a completion batch or observe it before its required causal dependencies are available. Duplicate delivery has no additional effect.

Durability means local persistence. Replication is asynchronous and requires no peer acknowledgment to complete a local commit. A committed result may therefore be unavailable elsewhere if its only holder goes offline before synchronization.

The selected model is operation batches with IDs, causal dependencies, typed operations and mandatory signatures. A batch identity is the emitting connection public key plus its durably increasing component of the post-increment vector clock. The full vector accompanies the batch as causal context. One writer per workspace replica serializes local edits, incoming batch integration and run commits; simultaneous processes under one replica identity are forbidden. Exact encodings and recovery belong in the companion document.

The same CRDT/commit mechanism stores the permanently private root workspace, with no sync connections. V1 keeps a complete append-only batch journal; local snapshots, compaction and snapshot-based bootstrap are deferred. A new replica replays available history. Immutable binary chunks are stored separately and referenced from the journal.

Convergence is expected for replicas receiving the same admissible change set. Coarse revocation can intentionally split membership and delivery sets; it does not promise convergence across mutually excluded groups.

### 6.2 Two equal-priority transports

Both HTTP and mounted shared-directory exchange are required in v1 and feed the same atomic batch-ingestion path. The filesystem deployment does not require clients to reach one another directly. Its file server is a transport availability dependency, not an application coordinator or workflow authority.

There is one transport per client-to-replica connection. A client can use different transports for different replica connections. This does not authorize two simultaneous transports on the same connection.

Accepted batches can be relayed across transport boundaries. Relaying preserves original operation identities, authorship, vector clocks and signatures; it does not create new logical edits. No mandatory central broker or permanent bridge peer is introduced.

Peer discovery information is synchronized workspace metadata. An HTTP connection exchanges missing batches in both directions even when only one replica initiates it; a reachable endpoint is still required and V1 has no NAT traversal. Filesystem outages are connectivity outages. Initial join replays complete available history; snapshots and compaction are deferred. Detailed discovery, partial-file handling, polling and retransmission are companion-design concerns, not optional correctness work.

### 6.3 Unsupported operations and unknown content

An unknown CRDT operation blocks its complete batch and causal dependents. It is never silently skipped. The affected workspace becomes read-only at its last supported state and local execution is suspended until upgrade. Already-started external effects can still finish; their recovery information remains local.

An unknown specialized block type is different: if its underlying operations are supported, content continues to synchronize and can use a generic JSON view. A missing specialized editor or executable capability does not by itself make the whole workspace read-only.

### 6.4 Graph projection and query boundary

YouTrackDB is the selected V1 graph implementation, embedded on JVM targets behind a graph SPI. The CRDT is the authoritative write model; the graph is a local, disposable read model.

A durable CRDT commit completes a write. Projection follows asynchronously. Each resolved committed batch updates the graph atomically together with its projection checkpoint. Replayed batches must not produce duplicate projected objects. A failed projection leaves the previous complete checkpoint available and can retry or rebuild.

Writes return a commit token. Reads may use the latest available projection or wait until its checkpoint includes that token. This is a local read-after-write facility, not a promise of global freshness. A missing projected object must not be treated as proof of authoritative deletion.

Live queries initially emit a complete result snapshot and then refreshed complete snapshots after relevant changes. Several commits may be coalesced, but each emitted result must correspond to a complete projection checkpoint. Incremental result-diff protocols are deferred.

The execution engine validates eligibility and commits against authoritative CRDT state. Projection lag, duplicate query notifications or a graph rebuild must never create a new execution attempt. A local restart can reconstruct the graph from durable state and resume projection.

The SPI exposes the application queries required by content and execution services. YouTrackDB-specific query objects and syntax remain in its adapter. General user-programmable database queries and vector search are not required foundations for V1.

## 7. Workflow definitions and reusable nodes

### 7.1 Representation

Workflows and nodes are typed documents. Process definitions have standard JSON/YAML representations. The visual graph editor and structured representations are views of the same definition, not independent sources of truth. Nodes are dedicated reusable objects rather than anonymous blocks owned by one workflow.

Many workflows can reference the same node definition. Each placement has its own configuration, including input mappings, edge conditions, and execution settings; this does not mutate the shared node.

Every node and workflow has a typed input/output contract. An invalid output is an invocation failure handled through the configured error policy.

### 7.2 Node categories

| Category | Role |
| --- | --- |
| Trigger/input | Introduces identified input into execution; v1 includes manual launch and form-based input |
| Internal computation | Transforms supplied inputs and proposes staged run-state changes; the owner interprets outgoing routing conditions |
| Side effect | Interacts with facilities outside the staged run computation, including resources bound to a host |

Database access and LLM inference are effects, even when they do not write data. Internal nodes can implement joins using run state. Special persistent internal-node state beyond that model is deferred. Side-effect nodes may maintain their own recovery state.

V1 expressions are structured JSON with a common-Kotlin evaluator. They support the operations needed for conditions and simple computations. A textual syntax and general-purpose scripting runtime are deferred. The exact initial operator catalog still requires specification.

### 7.3 Definition validity and freezing

Concurrent edits can leave a graph non-executable. Such definitions remain editable drafts with errors exposed. Instantiation must validate the graph, references, contracts, required capabilities, and allocations. Invalid definitions cannot become committed runs.

Each run owns a frozen copy of its workflow model, including resolved reusable node definitions/configuration and referenced workflow definitions, using exact type identities. Later definition edits affect new runs only. Recursive references must remain representable without infinitely expanding the frozen model. A UI can indicate that a referenced workflow has changed and offer an explicit update for future runs. It must not upgrade an existing run's model.

Allocation is frozen at creation. Submitted human inputs, attempt/recovery records and permitted agent-budget adjustments are runtime data. Each frozen executable identifies its supported execution-contract version. Compatible implementation fixes may retain that version; changed behavior requires a new one. A target lacking that contract waits for compatible support on the same replica; allocation cannot change.

### 7.4 Loops, edges, and joins

Loops and direct or indirect recursion are supported. Each visit creates a distinct activation identity.

All outgoing edges whose conditions evaluate true are activated. There is no implicit first-match-only rule. Specialized nodes can explicitly control fan-out. Outgoing conditions are evaluated once against the same post-node staged run state, and branch selection commits with the node result.

Branches have explicit order in the frozen definition. V1 uses depth-first ordering only. Matching successors are prepended to the pending work list in definition order. There is no breadth-first option, fairness rule or renumbering of existing invocation identities. An endless first branch can starve its siblings; this is accepted workflow behavior.

Multiple incoming activations trigger separate invocations. There is no automatic join barrier; a joining node implements correlation and accumulation using run state. It returns `ACK` when it accepted an input but is still accumulating: its proposed state is committed, no outgoing edges activate, and the next pending invocation can proceed. A later invocation can produce an ordinary successful output. `ACK` is an invocation outcome, not a run-wide wait or an error.

## 8. Run creation, allocation, and ownership

### 8.1 Creation and placement

A run is an engine-managed document in the same workspace as its workflow definition, discoverable through the ordinary document tree. Its folder uses the workspace default, workflow override, then creation override. The generated display name may include workflow name and date/time; it is not an execution identity.

Creation validates and freezes the workflow, referenced node/sub-workflow definitions, exact type identities, execution-contract versions, explicit inputs and allocation. A committed run has no unresolved execution target.

### 8.2 Fixed allocation

Allocation prefers the starting client where eligible. Advertised capabilities remain discoverable while peers are offline. Hard-bound resources stay pinned to their hosting replica. Missing capabilities or unresolved requirements prevent creation; a known but offline eligible target is valid.

The user may review or override allocation before creation, according to their preference. After creation it is immutable. There is no rerouting, reassignment, failover or takeover, including by the owner. A retry uses the same target. Starting another run is the way to choose another allocation; already performed effects are not undone.

Allocation covers reachable frozen node definitions, sub-workflows and the allowed agent tool set up front. Loops and recursion reuse those bindings for fresh invocations. They do not discover new unallocated executable definitions at runtime. The finite frozen definition graph permits recursion without pre-creating infinitely many invocations.

### 8.3 One owner replica

The starting workspace connection/replica owns the run for its lifetime. Only its engine writes authoritative run transitions. Only the original user on that replica can supply control actions: form answers, proposal decisions, Continue, cancellation or authorization to retry an uncertain effect.

Other peers may read synchronized run state and use committed outputs in their own work. They may execute requests assigned to them and author separate worker-result records. These records are proposals to the owner, not direct mutations of authoritative run state.

The original user opening another independently identified client does not acquire ownership. There is no ownership transfer. An offline owner prevents further run advancement even when all workers are reachable. Permanent loss of its identity/durable state can leave the run permanently stuck; starting a new run does not recover or reverse the old one.

Run and protocol envelopes are engine-managed. Full-write workspace membership does not authorize arbitrary edits to these envelopes or another run's control actions. The engine admits transitions only from the recorded owner, and worker records only from the assigned target with matching request identity. This is protocol validation, not a new general per-document ACL system. Cryptographic assurance still depends on the workspace signing policy.

## 9. Execution semantics

### 9.1 Sequential owner interpreter

Each run has one ordered pending work list and at most one outstanding invocation. The owner takes its first item, creates an execution request for the fixed target, and waits. It does not dispatch siblings while the invocation is unresolved.

An unavailable worker, unavailable capability, missing input bytes or unsupported execution contract blocks the next invocation. A timeout or apparent disconnection does not authorize another attempt. An unavailable owner prevents dispatch or result consumption. Already-dispatched work may finish and await the owner's return.

All scheduling and outgoing routing evaluation occur on the owner. Workers execute their assigned node and return its outcome, output and proposed run-state operations. They never choose or dispatch the next workflow node. Pure code may calculate a routing value, but the owner's interpreter applies the graph's edge rules.

### 9.2 Minimal durable records

The following logical shapes are an implementation outline, not a wire schema. They use the ordinary typed document/block model and workspace CRDT.

| Record | Author | Minimum content |
| --- | --- | --- |
| Run | Owner only | Owner identity, frozen definitions/contracts, fixed bindings, committed scratchpad, pending work list, current invocation/attempt, lifecycle/wait reason, consumed outcomes and publication state |
| Execution request | Owner only | Run/invocation/attempt IDs, target, frozen node contract/configuration, input values, required run-state snapshot and predecessor commit |
| Worker attempt record | Assigned worker only | Request identity, durable started/recovery status, locally needed recovery metadata or references |
| Worker result | Assigned worker only | Request identity, outcome, outputs, proposed state-write operations and error/recovery information |

Requests are immutable once issued. A final result is durably recorded before being exposed for synchronization and is immutable once published. Its identity is derived from its request so retransmission does not create another logical result. Large immutable snapshots may be referenced by durable content identity; a reference must not resolve against changing live state.

Worker recovery metadata that is private to the host stays local. Durable data required by the owner to consume an outcome travels in the result. Optional diagnostic traces are governed separately. Protocol records are retained for active recovery; they are not an additional message broker, transport, global event bus or independently compacted execution log.

### 9.3 Dispatch, compute and commit

1. The owner resolves inputs from committed state when preparing the invocation's attempt, waits for required data, and records the immutable request and current-attempt marker atomically.
2. The worker admits only a matching owner request addressed to itself. It checks local capability/blocking state and persists an attempt-start record before calling the node.
3. The node executes against the supplied snapshot with volatile staged changes. Reading a changing external resource is a side effect, not an implicit addition to pure evaluation.
4. The worker durably records its outcome, then publishes the result through normal CRDT synchronization. Re-delivery of the same request returns the existing outcome or attaches to its existing attempt; it does not start another concurrent execution.
5. The owner matches run, invocation, attempt, target and predecessor; checks that the result is current and unconsumed; and validates the output contract and proposed writes.
6. For successful output, the owner stages those writes, evaluates outgoing conditions against the resulting state and creates successor invocations. For `ACK`, it stages the accumulation and creates no successors.
7. One atomic owner batch consumes the result, commits permitted changes and invocation outcome, removes the current item, updates the work list and records lifecycle/publication changes where applicable.
8. The owner may then issue the next request. It may include that request in the preceding batch if all its inputs are already determined; no partial transition may become visible.

A worker on the owner's own client obeys the same logical contract. Local calls may avoid transport overhead without inventing a second state model.

### 9.4 Depth-first work list

The pending list's order is authoritative owner state. With pending siblings `[B]`, successful `A` activating edges `[X, Y]` produces `[X, Y, B]`. If `X` activates `[X1]`, the list becomes `[X1, Y, B]`. Invocation identity remains independent of list position.

Edges are considered in explicit frozen definition order, and every true condition activates a successor. Every arrival at a node is a fresh invocation, including multiple arrivals through different edges, loop iterations and repeated calls. The owner assigns those identities and persists them with the transition. A replay reuses committed identities.

`ACK` removes the current invocation after committing its accumulation. It does not retain the scheduler slot waiting for another branch. Join correlation/grouping is business logic held in the run scratchpad. A join only emits successors when its logic returns an ordinary successful outcome.

### 9.5 Run-state write semantics

The run has a specified internal envelope and a schema-free user scratchpad. Neither users nor remote workers edit committed run state directly. The owner's engine applies validated node proposals and permitted owner control actions.

Each scratchpad write specifies its semantics. A node can protect fields during its current processing; this does not permanently restrict later nodes. A write-once property locks its entire value recursively, including nested mutation and deletion/replacement through an ancestor. Repeating the same value produces a warning; a different value fails the invocation.

Failed attempts, invalid outputs, forbidden writes and routing errors publish none of the current attempt's staged scratchpad changes. Already committed data remains. External side effects cannot be rolled back by rejecting a result.

### 9.6 Failure and retry

The frozen workflow defines its failure policy: retry, error routing or termination. The owner commits the failure and its selected policy action atomically, without the failed attempt's staged changes. This also applies if a worker reports success but owner-side validation or outgoing-condition evaluation fails.

A retry retains the invocation ID and stable external operation ID, gets a new attempt ID, and uses the same fixed worker. The owner resolves fresh inputs when issuing that attempt; it never incorporates partial scratchpad writes from a failed attempt. Recovery/re-delivery of an existing request preserves its frozen snapshot.

A worker's external operation ID is stable across attempts of one invocation, allowing an adapter to use external idempotency support. Repeating an externally successful action because later validation failed remains a possible effect of the configured retry policy. The engine does not promise exactly-once external effects.

### 9.7 Restart and uncertain side effects

The worker first recovers an already durable result and republishes it. Pure computation with no recorded result may safely be recomputed automatically against the same attempt snapshot. External operations use adapter-specific reconciliation when available, for example querying a recorded job ID or idempotency key.

If the worker cannot determine whether a side effect completed, it records `UNCERTAIN` for the attempt. The owner puts the invocation into a wait requiring its user's decision. There is no automatic blind retry of an ambiguous effect, regardless of an ordinary retry policy.

The owner may explicitly authorize a new attempt on the same worker or terminate the run. Authorization acknowledges that the earlier effect may have happened. The old attempt is closed; late results for it cannot advance the run. External work may outlive a cancellation or uncertainty decision, so a logical sequential scheduler is not a guarantee that an external system has stopped processing.

The absence of a result from an offline worker does not prove uncertainty or failure: the run simply waits. Explicit adapter errors with a known outcome can follow ordinary failure policy. Detailed waiting information includes the blocked request and reason.

The owner reconstructs its current request, work list and consumed results from durable state after restart. A result committed before the owner crashed is not consumed twice. A result published while the owner was offline is consumed after it returns. A second process must not execute as the same live replica identity; local storage/process exclusivity belongs in the storage design.

### 9.8 Result admission and deduplication

A result must match the recorded worker and the current run/invocation/attempt request. Results for another target, closed attempt, canceled or terminal run, or already-consumed result do not advance execution. They may remain in retained diagnostics/history.

Request IDs prevent duplicate deliveries from starting duplicate attempts. Result IDs and the run's consumed marker prevent duplicate commits. The consumed marker, state contribution and work-list update are one atomic batch, so a crash cannot expose one without the others.

There are no competing owner histories to rank, no assignment revisions and no downstream invalidation algorithm. A peer must not fork an owner identity and independently author a second run history; V1 provides no reconciliation or takeover for that misuse.

### 9.9 Local blocking and waiting

A user may block a node on its hosting client. Existing processing finishes; future assigned invocations wait. This affects all runs using that capability. It does not change allocation or grant control over those runs.

Per-node limits across runs, retained child-wait slots and global/FIFO fairness policies are deferred. Host resource adapters may still have ordinary local queues or capacity limits. These are availability constraints, not new run scheduling semantics. There is no general synchronized pause-and-edit-run feature.

### 9.10 Lifecycle outline

These labels describe behavior; their serialized representation remains open. A small lifecycle plus a wait reason avoids a separate run status for every unavailable resource.

| Situation | Meaning |
| --- | --- |
| Active | Owner may dispatch or is awaiting an outstanding worker result |
| Waiting(reason) | No advancement until worker/data/capability, owner input, Continue, child completion or explicit uncertainty decision becomes available |
| SUCCESS | Normal completion with successful branch outcomes |
| FAILED | Normal failure outcome without a successful branch outcome |
| PARTIAL | Some branches succeeded and others failed |
| EXHAUSTED | No outstanding, queued or genuinely waiting work remains, but normal completion was not reached; includes an unsatisfied accumulating join with no remaining source of input |
| CANCELLED | Owner explicitly terminates through cancellation; committed data/effects remain |

`ACK` and `UNCERTAIN` are invocation/attempt outcomes, not additional successful or terminal run statuses. A locally observed owner outage need not be written as a shared status by another peer; it is an availability observation. Eager exits and their final outcome are explicit workflow behavior (§10).

## 10. Completion, publication, and sub-workflows

### 10.1 Completion and termination

An exit node can end its branch or eagerly terminate its run. An eager exit drops remaining undispatched work and uses its configured exit outcome. Otherwise branch outcomes accumulate; mixed successes and failures produce `PARTIAL`. No later work executes after a terminal commit.

An empty work list alone is not enough to complete a run if a worker request, owner input, child or other genuine wait is outstanding. Conversely, accumulated join data alone is not a promise of a future input: with no remaining work capable of advancing it, an unsatisfied join ends in `EXHAUSTED`.

Configured publication can copy committed run data to ordinary workspace content for terminal outcomes, including `PARTIAL` and `FAILED`. It is not restricted to successful runs. Failed-attempt staging is never included. Publication and the terminal run transition form one atomic owner batch when publication succeeds. Earlier committed effects and child outputs are not part of that rollback boundary.

If configured publication cannot produce a valid destination value, no partial publication is committed. The precise diagnostic/final-status treatment of publication failure remains a bounded implementation decision (§16), not justification for replaying a completed external action.

### 10.2 Workflow-as-node

A workflow can be used as a node. As compute, it waits for the child result. As an effect, its configuration chooses waiting or fire-and-forget. Child runs are documents in the same workspace and have the exact same owner replica as the parent. A remote call target never becomes a second coordinator.

The owner's interpreter creates and advances children. Child execution bindings use the frozen allocation selected at top-level startup; neither child creation nor recursion opens a new allocation/reassignment opportunity. Inputs are passed by copy according to the contract. Explicit typed references retain their reference meaning where resolvable in the same workspace.

Each child has its own sequential work list and scratchpad. A synchronous parent holds its call pending while the child runs. A fire-and-forget child may progress independently of later parent invocations; the one-invocation rule applies per run. Per-node cross-run capacity accounting is deferred.

### 10.3 Stable child identity and results

A child ID derives from parent run ID and call invocation ID, excluding retry count. Recovery or retry of the call finds the same child. Another loop iteration or recursive call has a fresh invocation and child identity.

Child creation must be durable before a fire-and-forget parent advances. A synchronous parent consumes the child's committed output once, copies the result into its own state and advances atomically. A child's exit terminates only that child; parent termination must be explicitly modeled. There is no exit escalation.

### 10.4 History and deletion

Active state retains the inputs, requests, results and outcomes required for execution recovery. Post-completion retention is a user preference; exact provider payloads and extra traces use the separate diagnostic policy (§12.7). Compaction must not remove data required by an active run.

Active run documents cannot be deleted; they must first become terminal. A permanently unavailable owner can therefore leave an active run that V1 cannot terminate or delete through normal run controls. This follows the accepted no-takeover rule. Deleting an AI response block does not delete or cancel its run.

Once output has been published into ordinary workspace content, it has normal collaborative editing semantics. This does not grant anyone the right to rewrite the source run's engine-managed history.

## 11. Capabilities, models, and tools

Capabilities are advertised as synchronized workspace data. Allocation checks node requirements against them. A capability disappearing after allocation blocks the assigned job until intervention rather than causing opportunistic reassignment.

Specific resources, including database and model connections, are hosted by pinned side-effect nodes. Connection credentials remain in the hosting client's private root. There is no additional per-member capability ACL in v1.

V1 model connectors support OpenAI-compatible endpoints. The user provides an endpoint; discovery proposes models and capabilities, including tool calling, modality support, structured output, and reasoning controls where discoverable. Users can override the result. Exact protocol variants and discovery probes belong to connector implementation design.

Some agent tools are built in; user-defined tools are reusable workflow nodes. Each agent definition exposes its chosen tool set. Its callable node placements are allocated up front, even if the model never invokes them. A model response requesting multiple tools executes them sequentially in the listed order, preserving the run's sequential execution rule.

## 12. Document-based agents

### 12.1 Invocation surface

There is no dedicated chat UI. Any document supports **Send to AI**. Selected blocks are submitted; if none are selected, the document is submitted. An optional instruction field adds a request without requiring an instruction block in the document.

The command selects an agent or model, effort where supported, and a per-command mode. Selecting a model directly invokes a built-in default agent workflow. Command defaults come from the chronologically latest AI call in that document, not from private client settings. The exact shared ordering used for that UI default is not a workflow scheduling rule.

Each ordinary submission creates a fresh run with submitted context. Conversation history is document content, not an implicit long-lived chat run. Persistent agent memory, if desired, is explicit workflow behavior.

### 12.2 Context and write targets

Commands can add other documents in the same workspace as context or explicit write targets. Individual block selection is limited to the current document in v1; external documents are selected as whole documents.

The command must explicitly designate blocks or documents that can be rewritten or receive additions. Without these targets, the agent only appends its response at the end of the originating document. Earlier content is not edited implicitly.

Target restrictions are per-command tool boundaries, not a return of per-document membership grants. Read context does not imply write permission for that command. The agent's exposed tool set and the command's allowed targets are both enforced.

Agents can create new documents, including typed documents and documents from templates, when given an explicit destination workspace/folder in the current workspace. Creating nested blocks requires explicit access to a container; an AI response does not implicitly grant unrestricted container creation.

### 12.3 Forms and schemas

An agent with an input contract produces a generated launch form. Explicit agent mappings prepopulate fields from selected content. Where multiple valid distributions remain, the first lexicographic distribution over stable field/block IDs is proposed. The user can edit it.

User validation of a schema-based launch form is mandatory even when autofill populates every field. This is distinct from optional allocation review or ordinary typed-form automatic commit preferences.

An agent can request human input through an inline form. Only its owner user on the owning replica can answer and resume the run. A form has an identified pending continuation point; consuming an answer closes it atomically. Repeated clicks or delayed submissions for that point cannot answer a later form or advance the run twice. Other peers may see the form but cannot control it.

### 12.4 Responses, streaming, and content representation

Submission immediately appends an AI response placeholder at the document end. It exposes progress and eventually a result or failure. Model streaming is shown when available; synchronization of in-progress streams to other peers is opt-in and uses CRDT synchronization only.

Every block provides a text fallback. The default is JSON describing its current content state; specialized types can provide a more readable representation. For LLM context, references resolve into source content. Internal tool calls follow their typed contracts instead of automatically dereferencing everything.

The context-sizing policy is replaceable. V1 truncates context from its beginning, retaining its end. Prompt order is **system prompt → retained context tail → instructions**. The response records that truncation occurred. This policy does not authorize truncating system instructions or silently changing tool contracts.

### 12.5 Review and acceptance

Each command chooses direct application or review before commit. In review mode every block is independently reviewable, including the document block itself. New documents are staged through the same block-review mechanisms; they do not need a separate approval subsystem.

Only the run owner can accept or reject its proposals. The decision addresses an identified pending proposal/block and is consumed once. Applying the selected content and recording acceptance are one atomic owner batch. A delayed duplicate or contrary decision does not undo acceptance. Accepted generated content becomes ordinary collaboratively editable content with provenance linking to the AI call.

Concurrent AI calls in one document are allowed. Each has its own run and response block. If a write target changes, the system gives a prominent warning. If it is deleted, the targeted request/application is blocked rather than recreating the identity or silently retargeting.

### 12.6 Retry, cancellation, limits, and Continue

The owner user on the owning replica can request best-effort cancellation through the response block. Committed changes and completed external effects are not undone. This is not a general pause-and-edit-run mechanism.

Retrying a failed AI call creates a new run and response block, preserving the prior attempt. It reuses the original submitted context snapshot, with a warning if sources changed. This command-level retry is distinct from an in-run node retry, which recomputes its inputs at execution time.

Agents have configurable model-turn and tool-call limits. User configuration chooses whether reaching a limit terminates the run or leaves it waiting for Continue. Committed progress remains in either case. An unfinished waiting agent can resume from its saved state; a finished agent has no Continue action and is never reopened or silently replaced by a successor run.

Only the owner user on the owning replica can Continue a waiting agent. Each command names its exact continuation point; duplicate or delayed commands for a consumed point have no effect. A later continuation point can accept a new command. A simple-chat next-turn command creates a fresh call with context rather than reopening the previous terminal run.

### 12.7 Provenance and diagnostic retention

Responses retain the provenance and operational data required for their supported actions, including links to the run, submitted context, model/agent, and author. Keeping exact serialized provider requests is a separate concern from retaining the semantic inputs needed for retry and continuation.

Exact model inputs and detailed model/tool diagnostics are session-local on the executing client by default. They are persisted to the CRDT only on opt-in. Diagnostic persistence uses a shared workspace default and a per-call override.

Other peers do not fetch session-local diagnostics over a separate channel in v1. Persisting them makes them available through ordinary synchronization. Execution data required for active runs, recovery, and Continue is not optional diagnostic data.

The response block offers expandable tool/execution details to the extent those details are locally available or persisted. The truncation indicator is retained even when the exact provider request is not.

## 13. Presentation contracts required from the engine

Detailed UI design is outside this specification, but the following behaviors affect stored state and engine APIs:

- Left navigation and a central content area; tabs and split panes.
- Last-opened per-workspace configuration restored from the private root.
- An aggregated workspace/document navigation mode or current-workspace mode with a selector. Neither introduces nested-workspace semantics.
- Folders and documents in navigation; no mandatory individual-block tree or systematic block naming.
- Shared per-block view selection, including independent selections on reference blocks.
- User-selectable search scope across accessible independent workspaces; search does not authorize cross-workspace references or agent execution.
- A visual workflow graph editor and a graph view of a run's frozen model, showing its observed execution state.
- Node/workflow configuration using the same typed forms and block infrastructure.
- Runs accessible as ordinary documents; agents represented through document commands and response blocks.

Search results, UI views, and observed peer status can be locally stale. Presentation must not imply a globally current replica view or successful remote execution merely because work was committed locally.

## 14. Behavioral acceptance scenarios

These specify observable acceptance criteria, not a test framework.

| ID | Scenario | Required outcome |
| --- | --- | --- |
| C01 | Same admissible CRDT batches arrive in different orders with duplicates | Same resulting state, without duplicate logical delivery effects |
| C02 | A batch arrives before its dependency | Whole batch waits; no partial run transition |
| C03 | Restart after local commit before replication | Durable state and unsynchronized changes recover |
| C04 | Owner and workers communicate over HTTP | Fixed-worker workflow advances through owner commits |
| C05 | Peers only share an ordinary mounted directory | Identical execution behavior without direct HTTP |
| C06 | A peer relays between transports | Original batch identities/authorship remain; duplicates are harmless |
| C07 | Unsupported CRDT operation arrives | Entire batch blocked; workspace read-only and local execution suspended |
| C08 | Unknown renderer with supported underlying data | Synchronization continues; generic representation available |
| C09 | Required binary is incomplete | Wait; never consume partial bytes |
| C10 | Concurrent text edits | String CRDT retains concurrent contributions |
| C11 | Variant switch races old-payload edits | Coherent selected variant; old edits do not leak |
| C12 | Delete races edit or move | Delete wins; recovery uses a fresh identity |
| C13 | New type version has identical structure | Old instances retain their nominal identity |
| C14 | Workflow has incompatible contracts | Editable draft, rejected instantiation |
| C15 | Next invocation targets an offline worker | No sibling dispatch, reroute or new attempt |
| C16 | Owner offline while worker finishes | Worker durably stores result; no next dispatch until owner returns |
| C17 | Another peer edits run controls or requests reassignment | No admitted run transition; original owner and bindings remain |
| C18 | Request delivered repeatedly to its worker | Existing attempt/result reused; no duplicate concurrent invocation |
| C19 | Result delivered repeatedly to owner | State contribution and next-step creation occur once |
| C20 | Owner crashes before/after result-consumption commit | Recovery observes either the full transition or none of it |
| C21 | Worker crashes after durable result before publishing | Recovered result is republished without replaying effect |
| C22 | Worker restarts after an effect with unknown outcome | UNCERTAIN; explicit owner decision, no blind retry |
| C23 | Owner authorizes uncertain retry | Same target, new attempt ID; obsolete results cannot advance run |
| C24 | Pure calculation interrupted without recorded result | Safe automatic recomputation against its frozen snapshot |
| C25 | Node output, proposed write or exit expression fails validation | All staged changes discarded; failure policy committed atomically |
| C26 | A forks X,Y with pending B; X activates X1 | A,X,X1,Y,B order; loop invocations have fresh IDs |
| C27 | Join returns ACK | Accumulation commits; no outgoing edge; next pending work proceeds |
| C28 | Only unsatisfied join accumulation remains | EXHAUSTED, not an invented indefinite input wait |
| C29 | Successful and failed branches finish normally | PARTIAL with preserved branch outcomes |
| C30 | Failed run has configured valid output publication | Committed data may publish; failed-attempt staging is absent |
| C31 | Child creation/call replayed | Same logical child; same owner and frozen allocation |
| C32 | Child exits eagerly | No automatic parent termination |
| C33 | Non-owner sees a form/proposal | Readable; cannot answer, accept, reject or Continue the source run |
| C34 | Owner repeats a form answer or Continue click | Exact continuation point consumed once |
| C35 | Agent reaches budget or finishes | Configured wait/terminate; no Continue on a terminal agent |
| C36 | Active run deletion requested | Rejected; deleting response alone does not cancel run |
| C37 | AI call has no explicit write target | Append response only; earlier content unchanged |
| C38 | Launch form fully autofilled | Owner validation still required |
| C39 | Diagnostics persistence off | Other peers cannot fetch session-local traces through another channel |
| C40 | Graph projection is behind durable commit | Token-aware reads wait; engine does not infer execution readiness from lagging projection |
| C41 | Graph projection restarted/rebuilt | Complete checkpoints; no duplicate invocation from query refreshes |
| C42 | Members mutually revoke during a partition | Membership split accepted; no central repair or owner takeover |
| C43 | Admission races with revocation of its inviter | Valid concurrent admission survives; causally later admission by revoked inviter is invalid |

## 15. Superseded decisions

| Earlier proposal | Current baseline |
| --- | --- |
| Browser/mobile in initial platform scope | Desktop JVM and headless JVM peers; browser/mobile later |
| Nested workspaces/mounts in V1 | Flat independent workspaces |
| Fine-grained grants/read-only members | Full-write membership with coarse peer exclusion |
| Global client public identity | Persistent identity per workspace connection |
| Types mutable until instantiated | Published types immutable; new version has a new identity |
| JSON Schema as complete internal type system | Shared nominal algebraic types; external schemas are adapter mappings |
| Graph engine undecided / Neo4j considered | YouTrackDB behind a graph SPI |
| Workers directly commit run state and activate successors | One owner consumes worker results and computes every transition |
| Owner may reassign targets during partitions | Frozen targets; no rerouting or owner takeover |
| Competing histories and assignment precedence | No owner forks; one durable owner transition sequence |
| Per-fan-out depth/breadth-first configuration | Depth-first only, explicit edge order, ordered pending list |
| Scheduling encoded in sortable hierarchical step IDs | Stable invocation IDs separate from owner-maintained list position |
| Inputs resolved from worker's changing local run view | Owner freezes request inputs/state before dispatch |
| Per-node cross-run parallelism and retained child slots | Deferred; sequential execution per run |
| Unknown effect outcome can blindly follow generic retry policy | UNCERTAIN requires explicit owner retry/terminate decision |
| Any member can answer/review/Continue a run | Owner user on the exact owning replica only |
| Continue after agent completion | Finished agents have no Continue; waiting agents may resume |
| Mixed branch failures always fail entire run | Mixed success/failure yields PARTIAL |
| Publication only for successful runs | Configurable terminal publication includes PARTIAL and FAILED |
| Join accumulation is failure or scheduler blockage | ACK commits accumulation and releases the invocation |
| Failed attempts retain scratchpad contributions | Staged changes discarded; committed past unchanged |
| Arbitrary user edits of run state | Engine-managed envelope and scratchpad |
| Undo committed changes | Undo only uncommitted state; reversal is a new edit |
| Optional signatures governed by a workspace setting | Every batch signed by its emitting connection identity |
| HTTP/filesystem simultaneously on one connection | One transport per client–replica connection; mixing other connections allowed |
| One transport deferred | HTTP and mounted-directory sync both required in V1 |

## 16. Remaining design work and scope discipline

### 16.1 Small implementation contracts

The execution architecture no longer needs a general distributed scheduler, reassignment protocol or reconciliation of competing histories. Its remaining implementation work is bounded:

1. Encode the logical records and lifecycle transitions in §9 using the common type/document model; enforce owner/worker author rules at the mutation/admission boundary.
2. Define the initial structured-expression vocabulary and deterministic value semantics. Missing required fields, wrong types and invalid operations fail the attempt; explicit exists/default operations handle optional data. A false condition is distinct from an evaluation error. No silent coercion is implied.
3. Specify terminal publication failure handling, including missing or invalid configured outputs, without undoing committed effects or rerunning completed invocations.
4. Define the finite allocation representation for frozen recursive definitions and child calls. Bindings remain selected at top-level startup.
5. Define the graph SPI's required typed queries, projection token/checkpoint representation and rebuild procedure. Keep engine-specific syntax contained.
6. Choose bootstrap type identifiers and the compact core type-declaration grammar. Preserve immutable nominal identity and the existing algebraic type requirements; avoid a separate type registry as another source of truth.

These are concrete encoding/implementation decisions. They should not reopen accepted product policies or add configuration knobs without a demonstrated need.

### 16.2 Separate CRDT, storage and sync design

The companion draft records the approved type-to-CRDT mapping, five-operation logical mutation vocabulary, signed causal batch envelope, single writer, complete journal, full-history bootstrap and equal-priority HTTP/filesystem exchange. It distinguishes signed batch authorship from transport publication. Exclusion stops direct exchange; signed batches by formerly admitted authors remain eligible through admitted relays, with no retroactive cutoff. Exact encodings, merge algorithms, signed-byte canonicalization, identity/counter durability, atomic persistence, storage/process exclusivity, safe shared-directory publication, partial files and compatibility negotiation still need implementation detail.

It must include deterministic multi-replica fault tests for duplicate/delayed/out-of-order batches, restarts and interrupted transport writes. Coarse membership exclusion must not be presented as precise global revocation. HTTP and filesystem paths feed the same logical ingestion path.

Future compaction must preserve active-run recovery and explain stale-replica re-entry, tombstones and retained history before being enabled. V1 has no automatic binary garbage collection.

### 16.3 Deliberately deferred features

Browser/mobile replicas, nested workspaces, cross-workspace references/execution, automatic type migrations, fine-grained authorization, general-purpose scripting, additional trigger families, third-party plugins, custom form/renderer builders, rich invitation flows, NAT traversal, binary reclamation, advanced context policies and vector-search integration are deferred.

Execution additionally defers owner takeover, reassignment, mixed traversal strategies, cross-run per-node parallelism configuration and multiplayer run controls. Adding one later requires an explicit revision of the execution contract, not an unnoticed extension of a record.

### 16.4 Primitive admission rule

Before introducing a new subsystem, try to represent the feature as (1) a typed document/block, (2) an existing execution composition or node implementation, and (3) ordinary CRDT commits. Add a primitive only when a required invariant cannot be expressed through those boundaries.

A capability adapter is justified when interacting with a host resource. A renderer is justified for a useful view. Neither automatically gets its own persistence protocol, permission hierarchy, scheduler or lifecycle. Search remains a projection, forms remain typed views, and agents remain workflows.

## 17. First validation objective

Use the same small scenario for both equal-priority transports: two persistent replicas share typed content and run a workflow alternating between the owner and a fixed remote worker. Include a loop, a join returning ACK, a human-input wait and one effect with simulated ambiguous completion.

Disconnect the worker: the owner must wait without dispatching a sibling. Disconnect the owner after dispatch: the worker may complete but the next invocation waits. Restart each side, repeat requests/results and deliver old attempt results after an explicit uncertain retry. Verify there is no duplicated committed contribution, no reassignment and no blind replay of an ambiguous effect.

For filesystem transport, the clients communicate only through an ordinary mounted shared directory. For HTTP, they use reachable endpoints. Both use the same requests, results, CRDT batches and owner interpreter. Neither demonstration substitutes for the other.

Then expose that exact engine through a document command and generated forms. An AI response, review proposal and run view should exercise the same typed content and commit model. Rebuild the YouTrackDB projection and confirm that neither content identity nor execution state changes.
