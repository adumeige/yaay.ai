# yaay.ai

A local-first workspace, built with Kotlin Multiplatform and Compose Multiplatform.

## Modules

| Module | Responsibility |
| --- | --- |
| `crdt` | Platform-independent CRDT primitives and merge semantics |
| `graph` | Application query SPI, embedded YouTrackDB projection and reactive reads |
| `documents` | Documents and blocks, type system, and built-in type definitions |
| `desktopApp` | Compose desktop application |

Dependencies flow from `desktopApp` → `documents` → `crdt`, with the JVM document
integration using `graph` → `crdt`. Libraries use
`commonMain` and `commonTest`, with JVM as the initial target. Additional targets
can be added when needed; no Android, browser, or native SDK is required.
The desktop entry point lives in `jvmMain`, with UI in `commonMain`.

The libraries now implement an initial retained-history CRDT, signed causal batches,
typed validation, and a durable JVM replica. The desktop still opens a welcome
window. HTTP and directory adapters now exchange the same signed batches and
hash-verified binary chunks. Implementation is complete; the separate-machine
SMB/NFS acceptance gate is deferred to later manual testing.
See [implementation status](docs/mechanics-implementation-status.md) for verified
behavior and remaining acceptance work.

The [embedded graph and Flow API](docs/graph-projection-v1.md) provide the M3 read
model, token-aware reads, complete-result subscriptions and crash-safe rebuilding.

Runnable headless peer commands are documented in [headless-peer.md](docs/headless-peer.md).
The [protocol specification](docs/mechanics-protocol-v1.md) and
[acceptance audit](docs/mechanics-acceptance-audit.md) record implementation contracts
and verification limits, including the unrun separate-machine mounted-share gate.

## Development

Install JDK 25 and make it available to Gradle (normally through `JAVA_HOME`).
The checked-in Gradle wrapper supplies Gradle 9.2.1; no global Gradle install is needed.
Plugin versions are pinned in `gradle/libs.versions.toml`.

```sh
./gradlew build
./gradlew :desktopApp:run
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

```sh
# Run merge, type, signing, process-recovery, HTTP, and directory tests.
./gradlew :crdt:jvmTest :documents:jvmTest :graph:jvmTest

# Explore beyond the fixed seeds of the randomized convergence tests (failures print the seed).
./gradlew :crdt:jvmTest :documents:jvmTest -Pyaay.test.seeds=500 -Pyaay.test.seedStart=$RANDOM

# Package for the current OS (requires its native packaging tools).
./gradlew :desktopApp:packageDistributionForCurrentOS
```

Native installers must be built on their target OS: DMG on macOS, MSI on Windows,
and DEB on Linux. `build` compiles the desktop application without creating an installer.
Behavioral tests include seeded reordered histories, signature and compatibility
checks, typed mutation validation, separate JVM writer/recovery tests, shared
transport scenarios, hostile-responder tests for the sync client, and byte-level
fuzzing of the batch and envelope decoders. Directory tests currently run on a local filesystem; they do
not establish the required separate-machine mounted-share release gate.

## Design

The specifications and implementation milestones live in [docs](docs/).
The current Gradle/Kotlin Multiplatform structure supersedes the earlier Maven
proposal in the initial planning notes. No full acceptance milestone is claimed yet.
