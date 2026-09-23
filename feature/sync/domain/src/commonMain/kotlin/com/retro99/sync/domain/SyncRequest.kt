package com.retro99.sync.domain

/**
 * A request for synchronization work.
 *
 * Requests are deliberately backend-neutral. The shared implementation can
 * merge several requests without exposing a separate workflow for each
 * transport.
 */
data class SyncRequest(
    val reason: SyncTriggerReason = SyncTriggerReason.MANUAL,
    val scope: SyncScope = SyncScope.All,
    val urgency: SyncUrgency = SyncUrgency.ROUTINE,
    val routineSchedule: RoutineSyncSchedule? = null,
)

data class RoutineSyncSchedule(
    val dirtyWaitMs: Long,
    val intervalSincePreviousMs: Long?,
    val forcedByMaximumWait: Boolean,
)

sealed interface SyncScope {
    data object All : SyncScope

    data class Books(val bookIds: Set<String>) : SyncScope
}

enum class SyncTriggerReason {
    STARTUP,
    RECOVERY,
    FOREGROUND,
    LIFECYCLE,
    CONNECTIVITY,
    BOOK_OPEN,
    ROUTINE_PROGRESS,
    READER_CHECKPOINT,
    MANUAL,
}

enum class SyncUrgency {
    ROUTINE,
    URGENT,
}
