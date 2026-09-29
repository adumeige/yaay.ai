# Mechanics acceptance audit

This ledger maps the original acceptance plan to executable evidence. It does not
replace or reduce that plan. `Covered` means the named test asserts the required
local result; it does not waive the separate-machine mounted-share gate. `Partial`
means additional evidence is required. Method names below are searchable in
`crdt/src/jvmTest` and `documents/src/jvmTest`.

Commands: `./gradlew :crdt:jvmTest :documents:jvmTest`; full build `./gradlew build`.
The repository's existing Gradle/KMP structure supersedes the planning draft's Maven
command examples. Tests call the production resolver, validator and ingestion code.

## Structure requirements

| ID | Status | Evidence |
| --- | --- | --- |
| R01 | Covered | `registersCausalityDuplicatesAndAtomicRejection`: exact concurrent author-ID winner; seeded reordered reconstruction |
| R02 | Covered | Same test: observed later assignment wins; `threeEmitterChainDeliveredBackwardWaitsForCompletePrefixes` |
| R03 | Covered | `identifiedMapEntriesMergeFieldsDeleteAndRestoreWithNewIdentity`, `activeVariantNestedFieldsMergeIndependentlyAndSameFieldUsesRegisterOrder` |
| R04 | Covered | `activeVariantNestedFieldsMergeIndependentlyAndSameFieldUsesRegisterOrder`: exact nested shared-field winner, other fields retained |
| R05 | Covered | `nestedReplacementKeepsConcurrentOldPayloadEditsOutOfSelectedValue` |
| R06 | Covered | `registersCausalityDuplicatesAndAtomicRejection`; transport duplicate scenarios compare history counts |
| R07 | Covered | `missingPublishedTypeBuffersWholeParentAndChildBatch` and `causalHolesDoNotAdvanceFrontierOrExposePartialBatches` |
| R08 | Covered | `replacementAndVariantKeepPayloadInstancesIsolated`; nested product/list/sum replacement fixture |
| R09 | Covered | `activeVariantNestedFieldsMergeIndependentlyAndSameFieldUsesRegisterOrder` |
| R10 | Covered | `sameAnchorConcurrentInsertionsHaveExactStableOrder` asserts both stable IDs and exact anchor ordering |
| R11 | Covered | `concurrentMovesResolveDestinationAndCyclesAndDeletion` |
| R12 | Covered | `concurrentMovesChooseSmallestDestinationThenCausallyLaterMoveWins` |
| R13 | Covered | `concurrentMovesResolveDestinationAndCyclesAndDeletion`, `mixedEmbeddedAndListContainmentCyclesResolveWithoutSharingInstances` |
| R14 | Covered | `deletingOrdinaryContentHidesConcurrentEditsAndRestorationDoesNotRepairOldReference`, map entry and list deletion fixtures |
| R15 | Covered | Map restoration asserts fresh ID and old tombstone; `invalidFieldsWrongNominalReferencesAndMixedBatchesNeverCommit` retains broken reference identity |
| R16 | Covered | `textUsesUnicodeScalarsAndRetainsConcurrentContributions`, scalar-index/long-text and exact-position regressions |
| R17 | Covered | Register tests assert whole `Atom.Str` winner; text test asserts composed contributions |
| R18 | Covered | `identifiedMapEntriesMergeFieldsDeleteAndRestoreWithNewIdentity`; typed structural seeds include key replacements |
| R19 | Covered | `nestedProductListSumReplacementsAtTwoDepthsStayCoherent` |
| R20 | Covered | `seededStructuralCommandsConvergeAndPreserveTypes`: seeds 0–7, deterministic keys, 45 commands each, separate reconstructed replicas; scalar schedule seeds 0–11 |

## Type requirements

| ID | Status | Evidence |
| --- | --- | --- |
| T01 | Covered | `nominalIdentitiesAndPinnedAliasesRemainDistinctAcrossVersions` |
| T02 | Covered | Same test plus `genericMapKeySubstitutionAndRecordKeyCanonicalization` |
| T03 | Covered | Old/new alias definitions resolve to distinct pinned nominal targets |
| T04 | Covered | Two same-structure nominal definitions retain distinct IDs; original instance type remains pinned |
| T05 | Covered | `invalidFieldsWrongNominalReferencesAndMixedBatchesNeverCommit`; enum and incoming-invalid-scalar fixtures |
| T06 | Covered | Typed nested replacement creates new instance and selects it in one batch |
| T07 | Covered | Mixed invalid-field and invalid-sum batches leave snapshots unchanged |
| T08 | Covered | Published definition assignment rejected by typed boundary |
| T09 | Covered | Optional/enum switch fixtures validate tag and payload together |
| T10 | Covered | `genericAliasesRecursiveNominalTypesAndCoherentOptionalValues`; generic map K/V fixture |
| T11 | Covered | `recursiveNominalValuesAreFiniteAndReferencesStayExplicit` |
| T12 | Covered | `aliasOnlyCycleRejectedAndIncomingTypesUseTheSameBoundary` |
| T13 | Covered | Explicit Ref/Instance type rejection and generic view reference representation |
| T14 | Covered | None/Some transitions in type and typed-edit tests |
| T15 | Covered | `independentValidEditsMayBreakCrossFieldBusinessPredicates` asserts merged sum of 2 |
| T16 | Covered | `genericValue` has no renderer dependency; enum/sum generic representation and typed transport tests |
| T17 | Covered | `invalidSignaturesAndUnsupportedBatchesUseSameAtomicPathOnBothAdapters` includes blocked dependent and restart |

## Batch, causal and storage requirements

| ID | Status | Evidence |
| --- | --- | --- |
| B01 | Covered | `everySignedEnvelopeFieldIsProtectedAndValidOtherWorkspaceBatchIsRefused` |
| B02 | Covered | Duplicate delivery assertions and history counts across common and real adapters |
| B03 | Covered | `signaturesCanonicalEncodingEquivocationAndUnsupportedOperations` signs conflicting content under same ID |
| B04 | Covered | Correctly signed different-workspace batch rejected, separate from signature tampering |
| B05 | Covered | `threeEmitterChainDeliveredBackwardWaitsForCompletePrefixes` |
| B06 | Covered | Killed writer recovery and `durableUnsynchronizedCommitRetransmitsAfterReplicaRestart` |
| B07 | Covered | `processDiesAtEveryJournalBoundaryWithoutPartialBatchOrCounterReuse` halts child JVMs before acknowledgement |
| B08 | Covered | Child process halts at header/payload/checksum boundaries while committing a two-object batch; object counts assert all-or-none recovery |
| B09 | Covered | Concurrent caller test asserts unique increasing counters and complete object count |
| B10 | Covered | Separate live process cannot open same locked store |
| B11 | Covered | `killedProcessReplaysNominalIdentityTombstonesReferencesAndCompleteFrontier` compares the complete normalized snapshot across killed/restarted JVMs |
| B12 | Covered | HTTP and directory relay bootstrap after original author's store is closed |

## Membership requirements

| ID | Status | Evidence |
| --- | --- | --- |
| A01 | Covered | `admissionDependencyBuffersNewAuthorsAndRelayCannotAdmitUnknownAuthor` |
| A02 | Covered | Same test signs never-admitted author's batch and submits via admitted relay |
| A03 | Covered | `membershipAdmissionConcurrentWithExclusionSurvives` tests both arrival orders |
| A04 | Covered | Same test rejects inviter's admission after observing its exclusion |
| A05 | Covered locally | HTTP exclusion and `excludedPublisherIsIgnoredWhileAdmittedRelayKeepsOriginalBatch` |
| A06 | Covered locally | Both adapters accept original signed history through admitted relay |
| A07 | Covered locally | `mutualExclusionDuringPartitionDoesNotInventReconciliation` |

## Transport requirements and fault gates

| ID | Status | Evidence |
| --- | --- | --- |
| X01 | Covered locally | Shared in-memory/HTTP/directory content/blob trace; typed Text/list/sum trace |
| X02 | Covered | Initiator-only HTTP tests and separate JVM CLI peers; no listener on initiating peer |
| X03 | Covered locally | Interrupted exchange retry, actual truncated HTTP response, incomplete directory candidate retry |
| X04 | Covered locally | `controlledReverseDependencyDeliveryBuffersThroughBothActualAdapters` withholds the prerequisite and asserts an unchanged snapshot/frontier |
| X05 | Covered locally | Original publisher gone; remaining HTTP/directory relay serves unchanged signed history |
| X06 | Covered locally | `independentRelayProcessesPublishConcurrentlyAndReceiverDeduplicates`; simultaneous HTTP sessions and duplicate histories |
| X07 | Covered locally | 132-batch new-peer join spans inventory pages and fetches referenced content through both adapters |
| X08 | Covered locally | Invalid inner signatures, unsupported batch plus dependent, unchanged frontier and restart over both adapters |
| X09 | Covered locally | `interruptedHttpChunkSessionKeepsConsumerPendingAndRepairsCorruptLocalChunks`, directory delayed/corrupt transfer and waiting consumer fixtures |

Publisher termination before readiness, partial temporary payloads, termination after
ready publication, restart/republication and concurrent separate-process relays now
have executable tests. Local permission denial and restoration and a missing share or
workspace area are tested (`deniedShareAccessKeepsLocalCommitsAndRecoversWhenRestored`,
`pollReportsAMissingShareOrWorkspaceAreaAsDeferred`); actual mounted-share unmount and
restoration remain. Static malicious paths, symlinks and concurrent publisher-directory replacement are
tested. Descendant I/O uses pinned secure directory handles and cannot be redirected
by replacing an opened path. Providers without these handles are refused explicitly;
the configured mount root itself remains trusted local configuration.

HTTP fault coverage includes simultaneous sessions, signed stale inventory, truncated
response, delayed body deadline, outbound-only initiating peer, direct exclusion and
admitted relay. `HostilePeerTest` covers the initiating client's own response checks:
replayed session, response from a different admitted peer, batch bytes not matching the
requested hash, stalled or unsorted inventory pages, and an oversized HTTP body.
`CodecTest` round-trips every atom and operation kind and fuzzes the batch and envelope
decoders: mutated bytes are rejected or canonical and never verify. HTTP confidential transport is available through an HTTPS proxy;
the embedded listener uses HTTP and does not claim encryption.

The ordinary network-share release gate is **not run**. `findmnt -t nfs,nfs4,cifs`
found no mounted SMB/NFS filesystem on this host. No separate-machine access has been
provided. Local directory/process results do not prove this required external gate.

## Additional implementation invariants

- Private-root policy is persisted and immutable; sync rejects private roots.
- Counter state comes from the complete signed journal, with no separately advanced
  counter file. Journal checksums and signatures are verified before recovered state.
- Bootstrap history is validated in isolation and atomically installed; killed-process
  tests establish all-or-none local journal installation and retry.
- Missing keys beside a retained journal do not create a replacement identity.
  `identityInitializationCrashesNeverReusePublishedIdentityWithDifferentKeys` halts
  JVMs after temporary-file force, rename and directory force and checks recovered keys.
- Callers cannot mutate retained state through input/output collection backing stores.
- Fixed embedded ownership participates in containment cycle resolution; replacement
  selects fresh instances. Arbitrary cross-field business predicates are not claimed.
- Chunks remain outside the journal, are verified before delivery, and are never
  automatically garbage-collected. No history compaction is implemented.
- Batch commit results include workspace, author/counter token, vector and signed
  operations for downstream services. M3 now implements embedded YouTrackDB and
  Kotlin Flow projection; see [graph-projection-v1.md](graph-projection-v1.md). The
  workflow interpreter remains a later milestone.

The local requirement matrix is covered. The implementation goal is complete, with
the separate-machine ordinary mounted-share release gate explicitly deferred by the
user to later manual testing. That gate has not passed. The actual deployment
filesystem must also supply secure directory handles and pass the
documented interruption/unmount/recovery scenarios.
