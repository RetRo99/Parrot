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
        val repository = SyncDataRepository(
            pass,
            contextProvider,
            SyncOutboxPreflight(outbox),
        )

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
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
        )

        assertEquals(SyncResult.NotAuthenticated, repository.sync())
        assertTrue(pass.requests.isEmpty())
    }

    @Test
    fun preflightSelectsDispatchableEntriesWithoutDroppingPreservedMutations() = runTest {
        val outbox = RecordingOutbox().apply {
            eligibleEntries = listOf(
                testEntry(
                    mutationId = "pending-book",
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    state = SyncOutboxEntry.STATE_PENDING,
                ),
                testEntry(
                    mutationId = "dispatched-position",
                    entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                    state = SyncOutboxEntry.STATE_DISPATCHED,
                ),
                testEntry(
                    mutationId = "preserved-conflict",
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    state = SyncOutboxEntry.STATE_CONFLICT_PRESERVED,
                ),
                testEntry(
                    mutationId = "reader-settings",
                    entityType = SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
                    state = SyncOutboxEntry.STATE_PENDING,
                ),
            )
        }
        val preflight = SyncOutboxPreflight(outbox)

        val selected = preflight.selectEligible(
            remoteAccountId = "remote-account",
            maxEntries = 10,
            now = "2026-09-22T00:00:00Z",
            capability = SyncOutboxCapability(
                unsupportedEntityTypes = setOf(SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS),
            ),
        )

        assertEquals(
            listOf("pending-book", "dispatched-position"),
            selected.map { entry -> entry.mutationId },
        )
        assertEquals(listOf("remote-account"), outbox.eligibleAccounts)
        assertEquals(listOf("2026-09-22T00:00:00Z"), outbox.eligibleTimes)
        assertEquals(
            3,
            preflight.pendingCount(
                remoteAccountId = "remote-account",
                capability = SyncOutboxCapability(
                    unsupportedEntityTypes = setOf(SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS),
                ),
            ),
        )
        assertTrue(outbox.deletedEntityTypes.isEmpty())
    }

    @Test
    fun preflightScopesSelectionAndCountToTheRequestedAccount() = runTest {
        val outbox = RecordingOutbox().apply {
            eligibleEntries = listOf(
                testEntry(
                    mutationId = "account-a-pending",
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    state = SyncOutboxEntry.STATE_PENDING,
                    cloudUserId = "account-a",
                ),
                testEntry(
                    mutationId = "account-a-conflict",
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    state = SyncOutboxEntry.STATE_CONFLICT_PRESERVED,
                    cloudUserId = "account-a",
                ),
                testEntry(
                    mutationId = "account-b-pending",
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    state = SyncOutboxEntry.STATE_PENDING,
                    cloudUserId = "account-b",
                ),
            )
        }
        val preflight = SyncOutboxPreflight(outbox)

        val selected = preflight.selectEligible(
            remoteAccountId = "account-a",
            maxEntries = 10,
            now = "2026-09-22T00:00:00Z",
        )

        assertEquals(listOf("account-a-pending"), selected.map { entry -> entry.mutationId })
        assertEquals(2, preflight.pendingCount("account-a"))
        assertEquals(listOf("account-a"), outbox.eligibleAccounts)
        assertEquals(listOf("account-a"), outbox.pendingAccounts)
        assertEquals(
            setOf("account-a-pending", "account-a-conflict", "account-b-pending"),
            outbox.eligibleEntries.map { entry -> entry.mutationId }.toSet(),
        )
        assertTrue(outbox.deletedEntityTypes.isEmpty())
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
    val eligibleAccounts = mutableListOf<String>()
    val pendingAccounts = mutableListOf<String>()
    val eligibleTimes = mutableListOf<String>()
    val deletedEntityTypes = mutableListOf<String>()
    var eligibleEntries: List<SyncOutboxEntry> = emptyList()

    override suspend fun enqueue(entry: SyncOutboxEntry) = Unit

    override suspend fun bindUnassignedMutations(cloudUserId: String) {
        boundAccounts += cloudUserId
    }

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> {
        pendingAccounts += cloudUserId
        return eligibleEntries.filter { entry -> entry.cloudUserId == cloudUserId }
    }

    override suspend fun getEligible(
        cloudUserId: String,
        now: String,
    ): List<SyncOutboxEntry> {
        eligibleAccounts += cloudUserId
        eligibleTimes += now
        return eligibleEntries.filter { entry -> entry.cloudUserId == cloudUserId }
    }

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) = Unit

    override suspend fun markConflict(mutationId: String, error: String) = Unit

    override suspend fun delete(mutationId: String) = Unit

    override suspend fun deleteByEntityType(entityType: String) {
        deletedEntityTypes += entityType
    }

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

private fun testEntry(
    mutationId: String,
    entityType: String,
    state: String,
    cloudUserId: String = "remote-account",
): SyncOutboxEntry {
    return SyncOutboxEntry(
        mutationId = mutationId,
        cloudUserId = cloudUserId,
        entityType = entityType,
        entityId = mutationId,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        baseRevision = null,
        createdAt = "2026-09-22T00:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
        state = state,
    )
}
