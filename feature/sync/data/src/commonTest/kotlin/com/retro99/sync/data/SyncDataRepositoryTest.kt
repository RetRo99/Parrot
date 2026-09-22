package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
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
import kotlin.test.assertTrue

class SyncDataRepositoryTest {

    @Test
    fun concurrentRequestsShareOneFlightAndMergePendingScopeAndUrgency() = runTest {
        val pass = RecordingSyncPass()
        val contextProvider = RecordingContextProvider()
        val outbox = RecordingOutbox()
        val repository = SyncDataRepository(pass, contextProvider, outbox)

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
        assertEquals(listOf("remote-account"), outbox.boundAccounts.distinct())
        assertEquals(2, contextProvider.invocationCount)
        assertTrue(pass.contexts.all { it == contextProvider.context })
    }

    @Test
    fun preflightResultSkipsPassExecution() = runTest {
        val pass = RecordingSyncPass()
        val contextProvider = RecordingContextProvider(
            result = SyncExecutionResult.NotAuthenticated,
        )
        val repository = SyncDataRepository(
            syncPass = pass,
            executionContextProvider = contextProvider,
            syncOutboxDatabase = RecordingOutbox(),
        )

        assertEquals(SyncResult.NotAuthenticated, repository.sync())
        assertTrue(pass.requests.isEmpty())
    }
}

private class RecordingSyncPass : SyncPass {
    val requests = mutableListOf<SyncRequest>()
    val contexts = mutableListOf<SyncExecutionContext>()
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()

    override suspend fun execute(
        request: SyncRequest,
        context: SyncExecutionContext,
    ): SyncResult {
        requests += request
        contexts += context
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

private class RecordingContextProvider(
    val context: SyncExecutionContext = SyncExecutionContext(
        localProfileId = "profile",
        remoteAccountId = "remote-account",
    ),
    private val result: SyncExecutionResult<Nothing>? = null,
) : SyncExecutionContextProvider {
    var invocationCount = 0
        private set

    override suspend fun <T> withPinnedContext(
        operation: suspend (SyncExecutionContext) -> T,
    ): SyncExecutionResult<T> {
        invocationCount += 1
        @Suppress("UNCHECKED_CAST")
        return result as? SyncExecutionResult<T> ?: SyncExecutionResult.Ready(operation(context))
    }
}

private class RecordingOutbox : SyncOutboxDatabase {
    val boundAccounts = mutableListOf<String>()

    override suspend fun enqueue(entry: SyncOutboxEntry) = Unit

    override suspend fun bindUnassignedMutations(cloudUserId: String) {
        boundAccounts += cloudUserId
    }

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = emptyList()

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) = Unit

    override suspend fun markConflict(mutationId: String, error: String) = Unit

    override suspend fun delete(mutationId: String) = Unit

    override suspend fun deleteByEntityType(entityType: String) = Unit

    override suspend fun recordFailure(
        mutationId: String,
        nextAttemptAt: String,
        error: String,
    ) = Unit

    override suspend fun coalesce(
        entityType: String,
        entityId: String,
        entry: SyncOutboxEntry,
    ) = Unit

    override suspend fun clearAllData() = Unit
}
