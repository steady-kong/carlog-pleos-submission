package kr.carlog.pleos

import java.util.UUID

/** Transport-neutral draft only: no network client or background synchronization is installed. */
enum class SyncCollection { VEHICLE, RECORD, SCHEDULE, EVENT }
data class SyncKey(val collection: SyncCollection, val id: UUID)

/** An opaque server-issued revision, not a wall-clock timestamp. */
data class SyncRevision(val value: String) {
    init { require(value.isNotBlank()) }
}

data class SyncCursor(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Null base revisions have an explicit create-only meaning; no unconditional writes. */
sealed interface SyncPrecondition {
    data object CreateOnly : SyncPrecondition
    data class IfMatch(val revision: SyncRevision) : SyncPrecondition
}

/** JSON is kept out of generated toString output because it can contain location and notes. */
sealed interface SyncBody {
    class Upsert(val json: String) : SyncBody {
        init { require(json.isNotBlank()) }
        override fun toString(): String = "Upsert([redacted])"
    }
    data object Tombstone : SyncBody
}

/** mutationId identifies a logical operation and must be reused on retries. */
data class SyncMutation(
    val mutationId: UUID,
    val key: SyncKey,
    val precondition: SyncPrecondition,
    val body: SyncBody,
)

sealed interface SyncOutcome {
    val mutationId: UUID
    data class Applied(override val mutationId: UUID, val revision: SyncRevision) : SyncOutcome
    data class Conflict(override val mutationId: UUID, val currentRevision: SyncRevision?) : SyncOutcome
    data class Rejected(override val mutationId: UUID, val code: String) : SyncOutcome {
        init { require(code.isNotBlank()) }
    }
}

data class SyncRemoteChange(val key: SyncKey, val revision: SyncRevision, val body: SyncBody)
data class SyncPage(val changes: List<SyncRemoteChange>, val nextCursor: SyncCursor, val hasMore: Boolean)

interface SyncTransport {
    /** A partial or malformed result must never be interpreted as an acknowledgment. */
    suspend fun push(changes: List<SyncMutation>): List<SyncOutcome>
    suspend fun pull(after: SyncCursor?, limit: Int): SyncPage
}

/** Call before acknowledging any submitted mutation in local storage. */
fun validateSyncOutcomes(submitted: List<SyncMutation>, outcomes: List<SyncOutcome>) {
    val ids = submitted.map { it.mutationId }
    require(ids.size == ids.toSet().size) { "Duplicate submitted mutation ID" }
    val keys = submitted.map { it.key }
    require(keys.size == keys.toSet().size) { "Multiple mutations for the same record in one batch" }
    val returned = outcomes.map { it.mutationId }
    require(returned.size == returned.toSet().size && returned.toSet() == ids.toSet()) {
        "Sync response does not match submitted mutations"
    }
}
