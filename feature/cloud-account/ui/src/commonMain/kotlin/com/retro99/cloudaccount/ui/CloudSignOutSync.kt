package com.retro99.cloudaccount.ui

import com.retro99.sync.domain.SyncResult

/** A completed pass is not a drained queue; also check uploads and unswept history. */
internal suspend fun remainingChangesAfterSync(
    result: SyncResult,
    countPendingChanges: suspend () -> Int,
): Int? = if (result is SyncResult.Completed) {
    maxOf(result.pendingMutationCount, countPendingChanges())
} else {
    null
}
