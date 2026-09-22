package com.retro99.sync.data

import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDataRepositoryTest {

    @Test
    fun concurrentRequestsShareOneFlightAndMergePendingScopeAndUrgency() = runTest {
        val pass = RecordingSyncPass()
        val repository = SyncDataRepository(pass)

        val first = async {
            repository.requestSync(
                SyncRequest(
                    reason = SyncTriggerReason.BOOK_OPEN,
                    scope = SyncScope.Books(setOf("book-a")),
                    urgency = SyncUrgency.ROUTINE,
                ),
            )
        }
        pass.firstStarted.await()

        val secondReady = CompletableDeferred<Unit>()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            secondReady.complete(Unit)
            repository.requestSync(
                SyncRequest(
                    reason = SyncTriggerReason.LIFECYCLE,
                    scope = SyncScope.Books(setOf("book-b")),
                    urgency = SyncUrgency.URGENT,
                ),
            )
        }
        secondReady.await()
        pass.releaseFirst.complete(Unit)

        first.await()
        second.await()

        assertEquals(2, pass.requests.size)
        assertEquals(
            SyncRequest(
                reason = SyncTriggerReason.LIFECYCLE,
                scope = SyncScope.Books(setOf("book-a", "book-b")),
                urgency = SyncUrgency.URGENT,
            ),
            pass.requests[1],
        )
    }
}

private class RecordingSyncPass : SyncPass {
    val requests = mutableListOf<SyncRequest>()
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()

    override suspend fun execute(request: SyncRequest): SyncResult {
        requests += request
        if (requests.size == 1) {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        return SyncResult.Completed(
            pushedMutationCount = 0,
            pulledChangeCount = 0,
            pendingMutationCount = 0,
        )
    }
}
