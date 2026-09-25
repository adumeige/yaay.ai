# Running a headless peer

The `documents:runPeer` JVM task exposes the production typed mutation, journal,
HTTP and directory adapters. It uses JDK 21 and the existing Gradle wrapper.
Each store directory has one persisted connection identity and one exclusive
writer. Stop a serving process before opening that same store in another command.

```sh
./gradlew :documents:runPeer --args='/tmp/yaay-a demo init'
```

Record the printed `author` key as `A`. Initialize the second store using A as its
pinned workspace founder; record its printed author key as `B`:

```sh
./gradlew :documents:runPeer --args='/tmp/yaay-b demo init A'
./gradlew :documents:runPeer --args='/tmp/yaay-a demo admit B'
./gradlew :documents:runPeer --args='/tmp/yaay-a demo create-text "Hello 🌍"'
```

Replace `A` and `B` above with the full public-key strings. The workspace argument
(`demo`) must match on both stores. Identity files contain private key material and
must remain local; the share receives only signed content and referenced chunks.

## HTTP

In one terminal, serve A:

```sh
./gradlew :documents:runPeer --args='/tmp/yaay-a demo serve 127.0.0.1 8080'
```

In another terminal, synchronize B with A. A session both downloads and uploads;
B does not need an inbound endpoint:

```sh
./gradlew :documents:runPeer --args='/tmp/yaay-b demo sync-http A http://127.0.0.1:8080/sync'
./gradlew :documents:runPeer --args='/tmp/yaay-b demo create-text "Offline edit"'
./gradlew :documents:runPeer --args='/tmp/yaay-b demo sync-http A http://127.0.0.1:8080/sync'
./gradlew :documents:runPeer --args='/tmp/yaay-b demo state'
```

Use a reachable bind address for separate hosts. The built-in listener speaks HTTP;
an HTTPS reverse proxy can protect transport confidentiality. Signatures authenticate
batches and sessions independently of TLS. There is no NAT traversal.

## Mounted directory

Stop the HTTP process before using A's store from these commands. Both hosts must
see the same ordinary mounted directory; each may use its own local mount path.

```sh
./gradlew :documents:runPeer --args='/tmp/yaay-a demo publish /mnt/team-share'
./gradlew :documents:runPeer --args='/tmp/yaay-b demo poll /mnt/team-share'
./gradlew :documents:runPeer --args='/tmp/yaay-b demo publish /mnt/team-share'
./gradlew :documents:runPeer --args='/tmp/yaay-a demo poll /mnt/team-share'
```

The directory adapter currently requires a filesystem provider exposing secure
directory handles (tested on Linux/JDK 21). Unsupported providers are refused;
Windows directory-provider support is not claimed.

Repeat polling/publication after reconnects. An incomplete or corrupt ready candidate
is diagnosed and retried; orphan temporary files are ignored. Publication areas are
keyed by workspace and publisher hashes. Do not share the replica's local store or
journal as a mutable database. Two relays may publish the same signed batch in their
own areas; ingestion deduplicates its original author/counter identity.

A local-directory run proves local behavior only. The test plan additionally requires
an actual ordinary SMB/NFS share with separate processes on separate machines. Record
OS/JDK versions, filesystem/share type and mount options, commands, process termination
points, reconnection outcomes and final states when performing that release gate.
No such separate-machine gate has been run in this environment yet.

## Editing and membership

`create-text` prints a stable object ID. `edit-text OBJECT START DELETE INSERT` uses
Unicode scalar offsets, not UTF-16 offsets or grapheme clusters. `state` prints a
generic typed view; a specialized renderer is not needed to synchronize content.
`info` prints the persisted identity and current journal count.

`exclude KEY` refuses direct exchanges with that key. It does not recall distributed
copies and is not a global cutoff: an admitted relay can still carry signed changes
from an author that was admitted previously. Concurrent admission by an inviter whose
exclusion was not yet in that admission's causal context remains valid.

`init-private` and `private-info` support a permanently private root store; it uses
the same journal engine and refuses synchronization. The library API provides its
ordinary typed edit boundary; the CLI's text commands currently target shared stores.

## Repeatable local evidence

```sh
./gradlew :crdt:jvmTest :documents:jvmTest
```

`PeerProcessTest` launches separate JVM CLI processes, initializes identities, admits
a peer, creates typed text, publishes/polls a local directory, exchanges both directions
through an actual HTTP listener and compares reopened states. `DurabilityTest` kills
child JVMs at journal and bootstrap write boundaries. These process tests do not
simulate a storage device losing power or replace the separate-machine share gate.

When embedding the libraries, combine `TypedMutationValidator` with
`typedSyncEndpoint(replica, blobStore)`. The typed endpoint discovers content
references inside canonical map keys as well as the ordinary CRDT value references.
