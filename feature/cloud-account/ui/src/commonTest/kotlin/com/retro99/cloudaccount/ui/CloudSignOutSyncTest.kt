package com.retro99.cloudaccount.ui

import com.retro99.sync.domain.SyncResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CloudSignOutSyncTest {
    @Test
    fun completedPassWithRemainingMutationsDoesNotAllowSignOut() = runTest {
        assertEquals(3, remainingChangesAfterSync(SyncResult.Completed(50, 0, 3)) { 0 })
    }

    @Test
    fun pendingUploadsOrUnsweptHistoryPreventSignOut() = runTest {
        assertEquals(2, remainingChangesAfterSync(SyncResult.Completed(50, 0, 0)) { 2 })
    }

    @Test
    fun drainedPassAllowsSignOut() = runTest {
        assertEquals(0, remainingChangesAfterSync(SyncResult.Completed(50, 0, 0)) { 0 })
    }

    @Test
    fun unsuccessfulPassCannotAllowSignOut() = runTest {
        assertNull(remainingChangesAfterSync(SyncResult.Offline(0)) { error("Must not count after failed sync") })
    }

    @Test
    fun countFailureDoesNotPretendTheQueueIsEmpty() = runTest {
        assertFailsWith<IllegalStateException> {
            remainingChangesAfterSync(SyncResult.Completed(50, 0, 0)) { error("Database unavailable") }
        }
    }
}
