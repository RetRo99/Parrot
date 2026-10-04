package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.SyncAnalyticsEvent
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncStatus
import com.retro99.sync.domain.SyncPhase
import com.retro99.sync.domain.FileTransferStatus
import com.retro99.sync.domain.FileTransferStatusSource
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
        assertTrue(repository.observeStatus().value is SyncStatus.Running)

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
    fun contextIsPinnedPerExecutionWhenTheActiveProfileChangesBetweenPasses() = runTest {
        val pass = RecordingSyncPass()
        val contextProvider = RecordingContextProvider(
            context = SyncExecutionContext(
                localProfileId = "profile-a",
                remoteAccountId = "account-a",
            ),
        )
        val outbox = RecordingOutbox()
        val repository = SyncDataRepository(
            syncPass = pass,
            executionContextProvider = contextProvider,
            syncOutboxPreflight = SyncOutboxPreflight(outbox),
        )

        val first = async { repository.requestSync(SyncRequest(SyncTriggerReason.STARTUP)) }
        pass.firstStarted.await()
        val second = async {
            repository.requestSync(SyncRequest(SyncTriggerReason.RECOVERY))
        }
        contextProvider.context = SyncExecutionContext(
            localProfileId = "profile-b",
            remoteAccountId = "account-b",
        )
        pass.releaseFirst.complete(Unit)

        first.await()
        second.await()

        assertEquals(
            listOf("profile-a", "profile-b"),
            pass.contexts.map { context -> context.localProfileId },
        )
        assertEquals(
            listOf("account-a", "account-b"),
            outbox.boundAccounts,
        )
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
        assertEquals(
            SyncStatus.Disabled,
            repository.observeStatus().value,
        )
    }

    @Test
    fun missingProfileDatabaseDuringDiagnosticsRestoreDoesNotCrashSync() = runTest {
        val repository = SyncDataRepository(
            syncPass = RecordingSyncPass(),
            executionContextProvider = RecordingContextProvider(
                result = SyncExecutionResult.NotAuthenticated,
            ),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            syncCheckpointDatabase = object : SyncCheckpointDatabase {
                override suspend fun getCheckpoint(
                    destinationId: String,
                    remoteAccountId: String,
                ): SyncCheckpoint? {
                    error("No active user profile")
                }

                override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) = Unit

                override suspend fun clearAllData() = Unit
            },
        )

        assertEquals(SyncResult.NotAuthenticated, repository.sync())
        assertEquals(SyncStatus.Disabled, repository.observeStatus().value)
    }

    @Test
    fun restoresAndPersistsTerminalDiagnostics() = runTest {
        val pass = RecordingSyncPass()
        val checkpointDatabase = StatusRecordingCheckpointDatabase(
            SyncCheckpoint(
                destinationId = "__application_sync_status__",
                remoteAccountId = "__application__",
                cursor = null,
                updatedAt = "2026-09-22T12:00:00Z",
                status = "up_to_date",
                lastSuccessfulAt = "2026-09-22T11:59:00Z",
            ),
        )
        val repository = SyncDataRepository(
            syncPass = pass,
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            syncCheckpointDatabase = checkpointDatabase,
        )

        val request = SyncRequest(reason = SyncTriggerReason.STARTUP)
        val run = async { repository.requestSync(request) }
        pass.firstStarted.await()

        assertEquals(
            SyncStatus.Running(
                phase = SyncPhase.PULLING,
                completedItems = 1,
                totalItems = 2,
            ),
            repository.observeStatus().value,
        )

        pass.releaseFirst.complete(Unit)
        assertEquals(SyncResult.Completed(0, 0, 0), run.await())
        val completed = repository.observeStatus().value as SyncStatus.Completed
        assertEquals(0, completed.pushedCount)
        assertEquals(0, completed.pulledCount)
        assertEquals(0, completed.pendingCount)
        assertEquals("up_to_date", checkpointDatabase.checkpoint?.status)
        assertTrue(checkpointDatabase.checkpoint?.lastSuccessfulAt != null)
    }

    @Test
    fun executesConfiguredDestinationsAndAggregatesCompletedWork() = runTest {
        val destination = RecordingDestination(
            result = SyncResult.Completed(
                pushedMutationCount = 2,
                pulledChangeCount = 3,
                pendingMutationCount = 4,
            ),
        )
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(
                result = SyncResult.Completed(
                    pushedMutationCount = 1,
                    pulledChangeCount = 5,
                    pendingMutationCount = 6,
                ),
            ),
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            destinations = listOf(destination),
        )
        val request = SyncRequest(reason = SyncTriggerReason.CONNECTIVITY)

        assertEquals(
            SyncResult.Completed(
                pushedMutationCount = 3,
                pulledChangeCount = 8,
                pendingMutationCount = 10,
            ),
            repository.requestSync(request),
        )
        assertEquals(listOf(request), destination.requests)
        val status = repository.observeStatus().value
        assertTrue(status is SyncStatus.Completed && status.pendingCount == 10)
    }

    @Test
    fun oneUrgentLifecycleRequestFansOutThroughParrotAndStorytellerDestinations() = runTest {
        val parrotPass = ImmediateSyncPass()
        val storytellerDestination = RecordingDestination(
            result = SyncResult.Completed(0, 0, 0),
        )
        val repository = SyncDataRepository(
            syncPass = parrotPass,
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            destinations = listOf(storytellerDestination),
        )
        val request = SyncRequest(
            reason = SyncTriggerReason.LIFECYCLE,
            urgency = SyncUrgency.URGENT,
        )

        repository.requestSync(request)

        assertEquals(listOf(request), parrotPass.requests)
        assertEquals(listOf(request), storytellerDestination.requests)
    }

    @Test
    fun configuredStorytellerDestinationRunsWhenParrotCloudIsUnauthenticated() = runTest {
        val storytellerDestination = RecordingDestination(
            result = SyncResult.Completed(
                pushedMutationCount = 1,
                pulledChangeCount = 0,
                pendingMutationCount = 0,
            ),
        )
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(),
            executionContextProvider = RecordingContextProvider(
                result = SyncExecutionResult.NotAuthenticated,
            ),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            destinations = listOf(storytellerDestination),
        )
        val request = SyncRequest(reason = SyncTriggerReason.CONNECTIVITY)

        assertEquals(
            SyncResult.Completed(1, 0, 0),
            repository.requestSync(request),
        )
        assertEquals(listOf(request), storytellerDestination.requests)
    }

    @Test
    fun destinationFailureIsNotHiddenByACompletedCloudPass() = runTest {
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(),
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            destinations = listOf(
                RecordingDestination(result = SyncResult.Failed("Storyteller unavailable")),
            ),
        )

        assertEquals(
            SyncResult.Failed("Storyteller unavailable"),
            repository.sync(),
        )
        assertEquals(
            SyncStatus.Failed(
                error = "Storyteller unavailable",
                pendingCount = 0,
                canRetry = true,
            ),
            repository.observeStatus().value,
        )
    }

    @Test
    fun mergesActiveFileTransfersOverIdleSyncAndAggregatesProgress() = runTest {
        val upload = RecordingFileTransferStatusSource(
            FileTransferStatus(
                phase = SyncPhase.UPLOADING_FILES,
                activeItems = 1,
                totalItems = 3,
                bytesTransferred = 80,
                totalBytes = 200,
            ),
        )
        val download = RecordingFileTransferStatusSource(
            FileTransferStatus(
                phase = SyncPhase.DOWNLOADING_FILES,
                activeItems = 1,
                totalItems = 2,
                bytesTransferred = 50,
                totalBytes = 100,
            ),
        )
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(),
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            fileTransferStatusSources = listOf(upload, download),
        )

        upload.awaitFirstEmission()
        download.awaitFirstEmission()

        assertEquals(
            SyncStatus.Running(
                phase = SyncPhase.TRANSFERRING_FILES,
                completedItems = 3,
                totalItems = 5,
                bytesTransferred = 130,
                totalBytes = 300,
            ),
            repository.observeStatus().value,
        )
    }

    @Test
    fun failedFileTransferPublishesRetryability() = runTest {
        val source = RecordingFileTransferStatusSource(
            FileTransferStatus(
                phase = SyncPhase.UPLOADING_FILES,
                activeItems = 2,
                totalItems = 2,
                bytesTransferred = 10,
                totalBytes = 100,
                error = "Transfer interrupted",
                canRetry = false,
            ),
        )
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(),
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            fileTransferStatusSources = listOf(source),
        )

        source.awaitFirstEmission()

        assertEquals(
            SyncStatus.Failed(
                error = "Transfer interrupted",
                pendingCount = 2,
                canRetry = false,
            ),
            repository.observeStatus().value,
        )
    }

    @Test
    fun offlineResultRetainsQueuedCountInObservableStatusAndCheckpoint() = runTest {
        val checkpointDatabase = StatusRecordingCheckpointDatabase()
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(SyncResult.Offline(pendingMutationCount = 4)),
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            syncCheckpointDatabase = checkpointDatabase,
        )

        assertEquals(SyncResult.Offline(4), repository.sync())
        assertEquals(SyncStatus.Offline(pendingCount = 4), repository.observeStatus().value)
        assertEquals("offline", checkpointDatabase.checkpoint?.status)
        assertEquals(4, checkpointDatabase.checkpoint?.pendingMutationCount)
    }

    @Test
    fun offlineResultKeepsLastSyncTimeAcrossRestartAndRetry() = runTest {
        val lastSync = "2026-10-02T21:40:00Z"
        val checkpointDatabase = StatusRecordingCheckpointDatabase(
            SyncCheckpoint(
                destinationId = "__application_sync_status__",
                remoteAccountId = "__application__",
                cursor = null,
                updatedAt = "2026-10-03T12:00:00Z",
                status = "offline",
                pendingMutationCount = 3,
                lastSuccessfulAt = lastSync,
            ),
        )
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(SyncResult.Offline(4)),
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            syncCheckpointDatabase = checkpointDatabase,
        )
        repository.sync()
        assertEquals(SyncStatus.Offline(4, lastSync), repository.observeStatus().value)
        assertEquals(lastSync, checkpointDatabase.checkpoint?.lastSuccessfulAt)
    }

    @Test
    fun logsPrivacySafeRunMetricsWithoutPayloadOrAccountIdentifiers() = runTest {
        val analytics = RecordingAnalytics()
        val repository = SyncDataRepository(
            syncPass = ImmediateSyncPass(
                result = SyncResult.Completed(2, 3, 1),
            ),
            executionContextProvider = RecordingContextProvider(),
            syncOutboxPreflight = SyncOutboxPreflight(RecordingOutbox()),
            analytics = analytics,
        )

        repository.requestSync(
            SyncRequest(
                reason = SyncTriggerReason.RECOVERY,
                urgency = SyncUrgency.ROUTINE,
            ),
        )

        val event = analytics.events.single() as SyncAnalyticsEvent.RunCompleted
        assertEquals("RECOVERY", event.trigger)
        assertEquals("ROUTINE", event.urgency)
        assertEquals("completed", event.result)
        assertEquals(2, event.pushedMutationCount)
        assertEquals(3, event.pulledChangeCount)
        assertEquals(1, event.pendingMutationCount)
        assertTrue(event.parameters.keys.none { key ->
            key.contains("payload", ignoreCase = true) ||
                key.contains("account", ignoreCase = true)
        })
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
        reportPhase: com.retro99.sync.domain.SyncPhaseReporter,
    ): SyncResult {
        requests += request
        contexts += context
        reportPhase(SyncPhase.PULLING, 1, 2)
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

private class ImmediateSyncPass(
    private val result: SyncResult = SyncResult.Completed(0, 0, 0),
) : SyncPass {
    val requests = mutableListOf<SyncRequest>()

    override suspend fun execute(
        request: SyncRequest,
        context: SyncExecutionContext,
        reportPhase: com.retro99.sync.domain.SyncPhaseReporter,
    ): SyncResult {
        requests += request
        return result
    }
}

private class RecordingDestination(
    private val result: SyncResult?,
) : SyncDestination {
    val requests = mutableListOf<SyncRequest>()

    override suspend fun execute(
        request: SyncRequest,
        reportPhase: com.retro99.sync.domain.SyncPhaseReporter,
    ): SyncResult? {
        requests += request
        return result
    }
}

private class RecordingFileTransferStatusSource(
    initialStatus: FileTransferStatus?,
) : FileTransferStatusSource {
    private val status = MutableStateFlow(initialStatus)
    private val firstEmission = CompletableDeferred<Unit>()

    override fun observe(): Flow<FileTransferStatus?> = flow {
        emit(status.value)
        firstEmission.complete(Unit)
        emitAll(status)
    }

    suspend fun awaitFirstEmission() {
        firstEmission.await()
    }
}

private class RecordingContextProvider(
    var context: SyncExecutionContext = SyncExecutionContext(
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

private class StatusRecordingCheckpointDatabase(
    initialCheckpoint: SyncCheckpoint? = null,
) : SyncCheckpointDatabase {
    var checkpoint: SyncCheckpoint? = initialCheckpoint

    override suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint? {
        return checkpoint?.takeIf {
            it.destinationId == destinationId && it.remoteAccountId == remoteAccountId
        }
    }

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        this.checkpoint = checkpoint
    }

    override suspend fun clearAllData() {
        checkpoint = null
    }
}

private class RecordingAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()

    override fun logException(throwable: Throwable, message: String?) = Unit

    override fun logEvent(event: AnalyticsEvent) {
        events += event
    }

    override fun setUserId(userId: String?) = Unit
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
