package com.retro99.sync.domain.usecase

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncNowUseCaseTest {
    @Test
    fun requestsUrgentManualSyncThroughTheSharedRepository() = runTest {
        val repository = RecordingSyncRepository()
        val expected = SyncResult.Completed(
            pushedMutationCount = 2,
            pulledChangeCount = 3,
            pendingMutationCount = 1,
        )
        repository.result = expected

        val result = SyncNowUseCase(repository)()

        assertEquals(expected, result)
        assertEquals(
            SyncRequest(
                reason = SyncTriggerReason.MANUAL,
                scope = SyncScope.All,
                urgency = SyncUrgency.URGENT,
            ),
            repository.request,
        )
    }

    @Test
    fun forwardsLifecycleRequestWithoutChangingItsReasonOrUrgency() = runTest {
        val repository = RecordingSyncRepository()
        val request = SyncRequest(
            reason = SyncTriggerReason.LIFECYCLE,
            scope = SyncScope.Books(setOf("book-1")),
            urgency = SyncUrgency.ROUTINE,
        )

        SyncNowUseCase(repository)(request)

        assertEquals(request, repository.request)
    }
}

private class RecordingSyncRepository : SyncRepository {
    var request: SyncRequest? = null
    var result: SyncResult = SyncResult.Failed("not configured")

    override suspend fun sync(): SyncResult = result

    override suspend fun requestSync(request: SyncRequest): SyncResult {
        this.request = request
        return result
    }
}
