package kr.carlog.pleos

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SyncContractTest {
    private val first = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val second = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val key = SyncKey(SyncCollection.EVENT, UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"))
    private val change = SyncMutation(first, key, SyncPrecondition.CreateOnly, SyncBody.Upsert("{\"memo\":\"private\"}"))

    @Test fun receiptsMayArriveOutOfOrderButMustCoverEachMutationOnce() {
        val deletion = SyncMutation(second, SyncKey(SyncCollection.RECORD, key.id), SyncPrecondition.IfMatch(SyncRevision("r1")), SyncBody.Tombstone)
        val outcomes = listOf(SyncOutcome.Conflict(second, SyncRevision("r2")), SyncOutcome.Applied(first, SyncRevision("r1")))
        validateSyncOutcomes(listOf(change, deletion), outcomes)
        fun rejected(sent: List<SyncMutation>, received: List<SyncOutcome>) {
            try { validateSyncOutcomes(sent, received); fail("invalid receipt") }
            catch (_: IllegalArgumentException) { }
        }
        rejected(listOf(change, deletion), outcomes.take(1))
        rejected(listOf(change), outcomes)
        rejected(listOf(change, change), listOf(outcomes.last(), outcomes.last()))
        rejected(listOf(change, deletion.copy(key = key)), outcomes)
        rejected(listOf(change, deletion), listOf(outcomes.first(), outcomes.first()))
    }

    @Test fun opaquePayloadIsNotPrintedWithMutationOrRemoteChange() {
        assertFalse(change.toString().contains("private"))
        assertFalse(SyncRemoteChange(key, SyncRevision("r1"), change.body).toString().contains("private"))
    }

    @Test fun blankRevisionCursorAndPayloadAreRejected() {
        for (action in listOf<() -> Any>({ SyncRevision(" ") }, { SyncCursor("") }, { SyncBody.Upsert(" ") })) {
            try { action(); fail("blank value") } catch (_: IllegalArgumentException) { }
        }
    }
}
