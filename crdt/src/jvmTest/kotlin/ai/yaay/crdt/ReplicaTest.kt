package ai.yaay.crdt

import kotlin.random.Random
import kotlin.test.*

class ReplicaTest {
    private class World {
        val keys = List(3) { JvmBatchCrypto.generate() }
        val founder = keys[0].author
        val validation = MutationValidator { _, _, _, _ -> }
        fun replica(index: Int): ReplicaEngine = ReplicaEngine("workspace", keys[index].author, founder, keys[index], MemoryJournal(), validation)
        val peers = List(3) { replica(it) }
        init {
            val admission = peers[0].commit(keys.drop(1).map { Operation.Membership(it.author, true) })
            peers.drop(1).forEach { it.ingest(admission, founder) }
        }
        fun send(batch: SignedBatch, target: ReplicaEngine) = target.ingest(batch, founder)
        fun spread() {
            val batches = peers.flatMap { it.history }.distinct()
            peers.forEach { p -> batches.forEach { send(it, p) } }
        }
        fun create(shape: Shape, fields: Map<String, Atom> = emptyMap()): String {
            val id = peers[0].nextId(0)
            peers[0].commit(listOf(Operation.Create(id, "test", shape, fields)))
            spread()
            return id
        }
    }

    @Test fun registersCausalityDuplicatesAndAtomicRejection() {
        val w = World()
        val id = w.create(Shape.RECORD, mapOf("x" to Atom.Str("initial"), "y" to Atom.Str("initial")))
        val a = w.peers[0].commit(listOf(Operation.Assign(id, "x", Atom.Str("a"))))
        val b = w.peers[1].commit(listOf(Operation.Assign(id, "x", Atom.Str("b")), Operation.Assign(id, "y", Atom.Str("b-y"))))
        w.spread()
        val winner = if (a.batch.id.author > b.batch.id.author) "a" else "b"
        w.peers.forEach { assertEquals(Atom.Str(winner), it.snapshot[id].fields["x"]); assertEquals(Atom.Str("b-y"), it.snapshot[id].fields["y"]) }
        val later = w.peers[2].commit(listOf(Operation.Assign(id, "x", Atom.Str("later"))))
        assertEquals(IngestResult.APPLIED, w.send(later, w.peers[0]))
        assertEquals(IngestResult.DUPLICATE, w.send(later, w.peers[0]))
        val before = w.peers[0].snapshot
        assertFailsWith<IllegalArgumentException> { w.peers[0].commit(listOf(Operation.Assign(id, "x", Atom.Str("bad")), Operation.Delete("missing"))) }
        assertEquals(before, w.peers[0].snapshot)
        w.spread()
        w.peers.forEach { assertEquals(Atom.Str("later"), it.snapshot[id].fields["x"]) }
    }

    @Test fun replacementAndVariantKeepPayloadInstancesIsolated() {
        val w = World()
        val old = w.create(Shape.RECORD, mapOf("label" to Atom.Str("old")))
        val holder = w.create(Shape.SUM, mapOf("value" to Atom.Variant("some", old)))
        val replacement = w.peers[0].nextId(0)
        w.peers[0].commit(listOf(Operation.Create(replacement, "test", Shape.RECORD, mapOf("label" to Atom.Str("new"))), Operation.Assign(holder, "value", Atom.Variant("other", replacement))))
        w.peers[1].commit(listOf(Operation.Assign(old, "label", Atom.Str("concurrent"))))
        w.spread()
        w.peers.forEach {
            assertEquals(Atom.Variant("other", replacement), it.snapshot[holder].fields["value"])
            assertEquals(Atom.Str("new"), it.snapshot[replacement].fields["label"])
            assertEquals(Atom.Str("concurrent"), it.snapshot[old].fields["label"])
        }
    }

    @Test fun causalHolesDoNotAdvanceFrontierOrExposePartialBatches() {
        val w = World()
        val first = w.peers[0].nextId(0)
        val create = w.peers[0].commit(listOf(Operation.Create(first, "test", Shape.REGISTER, mapOf("value" to Atom.Str("first")))))
        w.send(create, w.peers[1])
        val later = w.peers[1].commit(listOf(Operation.Assign(first, "value", Atom.Str("second"))))
        val receiver = w.peers[2]
        val before = receiver.snapshot
        assertEquals(IngestResult.BUFFERED, w.send(later, receiver))
        assertEquals(before, receiver.snapshot)
        w.send(create, receiver)
        assertEquals(Atom.Str("second"), receiver.snapshot[first].fields["value"])
        assertTrue(receiver.pendingIds.isEmpty())
    }

    @Test fun concurrentMovesResolveDestinationAndCyclesAndDeletion() {
        val w = World()
        val root = w.create(Shape.LIST)
        val left = w.create(Shape.LIST)
        val right = w.create(Shape.LIST)
        w.peers[0].commit(listOf(Operation.Place(left, root), Operation.Place(right, root)))
        w.spread()
        w.peers[0].commit(listOf(Operation.Place(left, right)))
        w.peers[1].commit(listOf(Operation.Place(right, left)))
        w.spread()
        val state = w.peers[0].snapshot
        assertFalse(state[left].parent == right && state[right].parent == left)
        assertTrue(state[left].parent == root || state[right].parent == root)
        assertEquals(state, w.peers[1].snapshot)
        w.peers[0].commit(listOf(Operation.Place(left, null)))
        w.peers[1].commit(listOf(Operation.Delete(left)))
        w.spread()
        w.peers.forEach { assertTrue(it.snapshot[left].deleted); assertFalse(left in it.snapshot[root].children) }
    }

    @Test fun concurrentMovesChooseSmallestDestinationThenCausallyLaterMoveWins() {
        val w = World()
        val left = w.create(Shape.LIST)
        val right = w.create(Shape.LIST)
        val item = w.create(Shape.REGISTER, mapOf("value" to Atom.Str("same identity")))
        w.peers[0].commit(listOf(Operation.Place(item, left)))
        w.peers[1].commit(listOf(Operation.Place(item, right)))
        w.spread()
        w.peers.forEach { assertEquals(minOf(left, right), it.snapshot[item].parent) }
        w.peers[2].commit(listOf(Operation.Place(item, maxOf(left, right))))
        w.spread()
        w.peers.forEach { assertEquals(maxOf(left, right), it.snapshot[item].parent) }
    }

    @Test fun textUsesUnicodeScalarsAndRetainsConcurrentContributions() {
        val w = World()
        val text = w.create(Shape.TEXT)
        w.peers[0].commit(listOf(Operation.EditText(text, null, "A🌍\n")))
        w.spread()
        val positions = w.peers[0].snapshot[text].textPositions
        assertEquals(3, positions.size)
        w.peers[0].commit(listOf(Operation.EditText(text, positions[0], "one", setOf(positions[1]))))
        w.peers[1].commit(listOf(Operation.EditText(text, positions[0], "two")))
        w.spread()
        val result = w.peers[0].snapshot[text].text
        assertTrue("one" in result && "two" in result && "🌍" !in result && '\n' in result)
        w.peers.forEach { assertEquals(result, it.snapshot[text].text) }
        assertFailsWith<IllegalArgumentException> { w.peers[0].commit(listOf(Operation.EditText(text, null, "\uD800"))) }
    }

    @Test fun membershipAdmissionConcurrentWithExclusionSurvives() {
        val w = World()
        val newcomer = JvmBatchCrypto.generate()
        val admission = w.peers[1].commit(listOf(Operation.Membership(newcomer.author, true)))
        val exclusion = w.peers[0].commit(listOf(Operation.Membership(w.keys[1].author, false)))
        w.send(exclusion, w.peers[2])
        w.send(admission, w.peers[2])
        w.send(admission, w.peers[0])
        assertTrue(w.peers[0].canExchange(newcomer.author))
        assertTrue(w.peers[2].canExchange(newcomer.author))
        assertFalse(w.peers[2].canExchange(w.keys[1].author))
        w.send(exclusion, w.peers[1])
        assertFailsWith<IllegalArgumentException> { w.peers[1].commit(listOf(Operation.Membership(JvmBatchCrypto.generate().author, true))) }
        val id = w.peers[1].nextId(0)
        val relayed = w.peers[1].commit(listOf(Operation.Create(id, "test", Shape.TEXT)))
        assertFailsWith<ProtocolFault> { w.peers[2].ingest(relayed, w.keys[1].author) }
        assertEquals(IngestResult.APPLIED, w.send(relayed, w.peers[2]))
    }

    @Test fun signaturesCanonicalEncodingEquivocationAndUnsupportedOperations() {
        val w = World()
        val id = w.create(Shape.TEXT)
        val original = w.peers[0].commit(listOf(Operation.EditText(id, null, "hello")))
        assertEquals(original, BatchCodec.decode(BatchCodec.encode(original)))
        val tampered = original.copy(batch = original.batch.copy(workspace = "elsewhere"))
        assertFalse(w.keys[0].verify(tampered))
        assertFailsWith<ProtocolFault> { w.send(tampered, w.peers[1]) }
        w.send(original, w.peers[1])
        val conflict = w.keys[0].sign(original.batch.copy(operations = listOf(Operation.EditText(id, null, "other"))))
        assertFailsWith<ProtocolFault> { w.send(conflict, w.peers[1]) }
        val before = w.peers[1].snapshot
        val next = original.batch.id.counter + 1
        val unknown = w.keys[0].sign(original.batch.copy(id = BatchId(w.founder, next), vector = Frontier(original.batch.vector.counters + (w.founder to next)), operations = listOf(Operation.EditText(id, null, "not visible"), Operation.Unknown(99, listOf(1, 2)))))
        assertEquals(IngestResult.UNSUPPORTED, w.send(unknown, w.peers[1]))
        assertEquals(before, w.peers[1].snapshot)
        assertTrue(w.peers[1].readOnly)
        assertFailsWith<IllegalStateException> { w.peers[1].commit(listOf(Operation.EditText(id, null, "blocked"))) }
    }

    @Test fun delayedInvalidBatchCannotMakeDependencyCommitReportFailure() {
        val w = World()
        val author = w.peers[0]
        val prerequisite = author.commit(listOf(Operation.Membership(JvmBatchCrypto.generate().author, true)))
        val badId = BatchId(w.keys[1].author, 1)
        val invalid = w.keys[1].sign(Batch("workspace", 1, badId,
            Frontier(prerequisite.batch.vector.counters + (w.keys[1].author to 1L)), listOf(Operation.Delete("missing"))))
        assertEquals(IngestResult.BUFFERED, w.send(invalid, w.peers[2]))
        assertEquals(IngestResult.APPLIED, w.send(prerequisite, w.peers[2]))
        assertTrue(badId in w.peers[2].rejections)
        assertFailsWith<ProtocolFault> { w.send(invalid, w.peers[2]) }
        assertEquals(prerequisite.batch.id.counter, w.peers[2].snapshot.frontier[w.founder])
    }

    @Test fun callerOwnedCollectionsCannotRewriteAcceptedSignedHistoryOrSnapshots() {
        val w = World()
        val fields = mutableMapOf<String, Atom>("value" to Atom.Str("original"))
        val id = w.peers[0].nextId(0)
        val operations = mutableListOf<Operation>(Operation.Create(id, "test", Shape.REGISTER, fields))
        val committed = w.peers[0].commit(operations)
        fields["value"] = Atom.Str("mutated")
        operations.clear()
        assertEquals(Atom.Str("original"), w.peers[0].snapshot[id].fields["value"])
        val returnedFields = (committed.batch.operations[0] as Operation.Create).fields as MutableMap<String, Atom>
        returnedFields["value"] = Atom.Str("mutated again")
        val snapshotFields = w.peers[0].snapshot[id].fields as MutableMap<String, Atom>
        snapshotFields["value"] = Atom.Str("mutated snapshot")
        val historyFields = (w.peers[0].history.last().batch.operations[0] as Operation.Create).fields as MutableMap<String, Atom>
        historyFields["value"] = Atom.Str("mutated history")
        w.spread()
        w.peers.forEach {
            assertEquals(Atom.Str("original"), it.snapshot[id].fields["value"])
            assertTrue(w.keys[0].verify(it.history.last()))
        }
    }

    @Test fun everySignedEnvelopeFieldIsProtectedAndValidOtherWorkspaceBatchIsRefused() {
        val w = World()
        val id = w.create(Shape.TEXT)
        val original = w.peers[0].commit(listOf(Operation.EditText(id, null, "signed")))
        val b = original.batch
        val altered = listOf(
            b.copy(workspace = "other"), b.copy(version = 2),
            b.copy(id = b.id.copy(author = w.keys[1].author)), b.copy(id = b.id.copy(counter = b.id.counter + 1)),
            b.copy(vector = Frontier(b.vector.counters + (w.keys[1].author to 99L))),
            b.copy(operations = listOf(Operation.EditText(id, null, "tampered"))),
        )
        val before = w.peers[2].snapshot
        altered.forEach { changed ->
            val unsignedTampering = original.copy(batch = changed)
            assertFalse(w.keys[0].verify(unsignedTampering))
            assertFailsWith<ProtocolFault> { w.send(unsignedTampering, w.peers[2]) }
        }
        val otherWorkspace = w.keys[0].sign(b.copy(workspace = "other"))
        assertTrue(w.keys[0].verify(otherWorkspace))
        assertFailsWith<ProtocolFault> { w.send(otherWorkspace, w.peers[2]) }
        assertEquals(before, w.peers[2].snapshot)
    }

    @Test fun admissionDependencyBuffersNewAuthorsAndRelayCannotAdmitUnknownAuthor() {
        val w = World()
        val newcomer = JvmBatchCrypto.generate()
        val admission = w.peers[1].commit(listOf(Operation.Membership(newcomer.author, true)))
        val joining = ReplicaEngine("workspace", newcomer.author, w.founder, newcomer, MemoryJournal(), w.validation)
        joining.bootstrap(w.peers[1].history, w.keys[1].author)
        val first = joining.commit(listOf(Operation.Create(joining.nextId(0), "test", Shape.TEXT)))
        assertEquals(IngestResult.BUFFERED, w.send(first, w.peers[0]))
        assertFalse(w.peers[0].canExchange(newcomer.author))
        w.send(admission, w.peers[0])
        assertTrue(w.peers[0].canExchange(newcomer.author))
        assertEquals(1L, w.peers[0].snapshot.frontier[newcomer.author])
        val stranger = JvmBatchCrypto.generate()
        val bid = BatchId(stranger.author, 1)
        val forgedAdmissionByRelay = stranger.sign(Batch("workspace", 1, bid, Frontier(w.peers[0].snapshot.frontier.counters + (stranger.author to 1L)), listOf(Operation.Create(OpId(bid, 0).stableId(), "test", Shape.TEXT))))
        val before = w.peers[0].snapshot
        assertFailsWith<ProtocolFault> { w.send(forgedAdmissionByRelay, w.peers[0]) }
        assertEquals(before, w.peers[0].snapshot)
    }

    @Test fun threeEmitterChainDeliveredBackwardWaitsForCompletePrefixes() {
        val w = World()
        val id = w.peers[0].nextId(0)
        val first = w.peers[0].commit(listOf(Operation.Create(id, "test", Shape.REGISTER, mapOf("value" to Atom.Str("a")))))
        w.send(first, w.peers[1])
        val second = w.peers[1].commit(listOf(Operation.Assign(id, "value", Atom.Str("b"))))
        w.send(second, w.peers[2]); w.send(first, w.peers[2])
        val third = w.peers[2].commit(listOf(Operation.Assign(id, "value", Atom.Str("c"))))
        val rebuilt = w.replica(0)
        w.send(w.peers[0].history.first(), rebuilt)
        val before = rebuilt.snapshot
        assertEquals(IngestResult.BUFFERED, w.send(third, rebuilt))
        assertEquals(IngestResult.BUFFERED, w.send(second, rebuilt))
        assertEquals(before, rebuilt.snapshot)
        w.send(first, rebuilt)
        assertTrue(rebuilt.pendingIds.isEmpty())
        assertEquals(Atom.Str("c"), rebuilt.snapshot[id].fields["value"])
        assertEquals(w.peers[2].snapshot, rebuilt.snapshot)
    }

    @Test fun equivalentPublicKeyEncodingCannotCreateAlternateAuthorIdentity() {
        val w = World()
        val author = w.keys[0].author + "="
        val bid = BatchId(author, 1)
        val batch = Batch("workspace", 1, bid, Frontier(mapOf(author to 1L)), listOf(Operation.Create(OpId(bid, 0).stableId(), "test", Shape.TEXT)))
        val bytes = BatchCodec.logical(batch)
        val signed = SignedBatch(batch, BatchCodec.hash(bytes), w.keys[0].signBytes(bytes).toList())
        assertFalse(w.keys[0].verify(signed))
        assertFailsWith<ProtocolFault> { w.send(signed, w.peers[1]) }
    }

    @Test fun seededCausalDagsConvergeUnderArbitraryDeliveryAndDuplicates() {
        repeat(12) { seed ->
            val random = Random(seed)
            val w = World()
            val id = w.create(Shape.RECORD, mapOf("x" to Atom.Number(0.0), "y" to Atom.Number(0.0)))
            repeat(20) { step ->
                val p = w.peers[random.nextInt(3)]
                p.commit(listOf(Operation.Assign(id, if (random.nextBoolean()) "x" else "y", Atom.Number(step.toDouble()))))
                if (random.nextBoolean()) {
                    val source = w.peers[random.nextInt(3)]
                    source.history.shuffled(random).take(random.nextInt(source.history.size + 1)).forEach { w.send(it, p) }
                }
            }
            val history = w.peers.flatMap { it.history }.distinct()
            val rebuilt = List(3) { w.replica(it) }
            try {
                rebuilt.forEach { p -> (history + history.shuffled(random)).shuffled(random).forEach { w.send(it, p) } }
                rebuilt.forEach { assertTrue(it.pendingIds.isEmpty()); assertEquals(rebuilt[0].snapshot, it.snapshot) }
            } catch (failure: Throwable) {
                throw AssertionError("seed=$seed history=${history.map { it.batch }}", failure)
            }
        }
    }
}
