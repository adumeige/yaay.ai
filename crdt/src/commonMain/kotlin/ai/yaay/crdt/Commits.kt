package ai.yaay.crdt

/** Local read-after-write token; it makes no assertion about remote replication. */
public data class CommitToken(public val workspace: String, public val id: BatchId)
public fun SignedBatch.commitToken(): CommitToken = CommitToken(batch.workspace, batch.id)

/** A complete accepted batch and the resolved state immediately after its application. */
public data class CommittedBatch(public val signed: SignedBatch, public val snapshot: Snapshot) {
    public val token: CommitToken get() = signed.commitToken()
}

public data class ReplicaState(public val snapshot: Snapshot, public val readOnly: Boolean)
