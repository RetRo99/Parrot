package com.retro99.sync.data

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.FileTransferStatus
import com.retro99.sync.domain.FileTransferStatusSource
import com.retro99.sync.domain.SyncPhase
import com.retro99.sync.domain.SyncPhaseReporter
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncStatus
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.SyncAnalyticsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import kotlin.time.Clock
import kotlin.time.TimeSource
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Application-facing synchronization coordinator.
 *
 * All callers share one in-flight pass. Requests arriving during that pass are
 * merged and run after the current pass without starting a competing network
 * drain. This keeps manual, lifecycle, and background triggers on one path.
 */
@Single(binds = [SyncRepository::class])
class SyncDataRepository(
    @Provided private val syncPass: SyncPass,
    @Provided private val executionContextProvider: SyncExecutionContextProvider,
    @Provided private val syncOutboxPreflight: SyncOutboxPreflight,
    @Provided private val destinations: List<SyncDestination> = emptyList(),
    @Provided private val fileTransferStatusSources: List<FileTransferStatusSource> = emptyList(),
    @Provided internal val syncCheckpointDatabase: SyncCheckpointDatabase? = null,
    @Provided private val analytics: Analytics? = null,
) : SyncRepository {
    private val mutex = Mutex()
    private var activeRun: ActiveRun? = null
    private val syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle())
    private val transferStatuses = MutableStateFlow(
        List<FileTransferStatus?>(fileTransferStatusSources.size) { null },
    )
    private val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle())
    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diagnosticsLoadJob: Job = diagnosticsScope.launch {
        val checkpoint = try {
            syncCheckpointDatabase?.getCheckpoint(
                destinationId = DIAGNOSTICS_DESTINATION_ID,
                remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // The local profile database may not exist until onboarding completes.
            null
        }
        checkpoint?.toStatus()?.let { persistedStatus ->
            syncStatus.value = persistedStatus
            publishStatus()
        }
    }

    init {
        fileTransferStatusSources.forEachIndexed { index, source ->
            diagnosticsScope.launch {
                source.observe().collect { transferStatus ->
                    transferStatuses.update { current ->
                        current.toMutableList().also { statuses -> statuses[index] = transferStatus }
                    }
                    publishStatus()
                }
            }
        }
    }

    override fun observeStatus(): StateFlow<SyncStatus> = status.asStateFlow()

    override suspend fun sync(): SyncResult {
        return requestSync(SyncRequest())
    }

    override suspend fun requestSync(request: SyncRequest): SyncResult {
        val (run, owner) = mutex.withLock {
            val existing = activeRun
            if (existing != null) {
                existing.pendingRequest = (existing.pendingRequest ?: existing.activeRequest)
                    .merge(request)
                existing to false
            } else {
                ActiveRun(request).also { activeRun = it } to true
            }
        }

        if (!owner) return run.result.await()

        diagnosticsLoadJob.join()
        val lastSuccessfulAt = syncStatus.value.lastSuccessfulAt()
        val previousPendingCount = syncStatus.value.pendingCountOrNull() ?: 0
        reportPhase(SyncPhase.PREPARING)
        val startedAt = TimeSource.Monotonic.markNow()

        var lastResult: SyncResult = SyncResult.Failed("Synchronization did not execute")
        try {
            while (true) {
                val nextRequest = mutex.withLock {
                    val pending = run.pendingRequest
                    if (pending == null) {
                        if (activeRun === run) activeRun = null
                        null
                    } else {
                        run.pendingRequest = null
                        pending
                    }
                } ?: break

                run.activeRequest = nextRequest
                val execution = executionContextProvider.withPinnedContext { context ->
                    syncOutboxPreflight.bindUnassignedMutations(context.remoteAccountId)
                    syncPass.execute(nextRequest, context, ::reportPhase)
                }
                val cloudResult = when (execution) {
                    is SyncExecutionResult.Ready -> execution.value
                    SyncExecutionResult.NotConfigured -> SyncResult.NotConfigured
                    SyncExecutionResult.NotAuthenticated -> SyncResult.NotAuthenticated
                    SyncExecutionResult.ProfileNotLinked -> SyncResult.ProfileNotLinked
                    SyncExecutionResult.SyncDisabled -> SyncResult.SyncDisabled
                    is SyncExecutionResult.Failed -> SyncResult.Failed(execution.message)
                }
                val destinationResults = destinations.mapNotNull { destination ->
                    try {
                        destination.execute(nextRequest, ::reportPhase)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        SyncResult.Failed(
                            exception.message ?: "Synchronization destination failed",
                        )
                    }
                }
                lastResult = combineResults(cloudResult, destinationResults)
            }
            run.result.complete(lastResult)
            val terminalStatus = lastResult.toStatus(previousPendingCount, lastSuccessfulAt)
            syncStatus.value = terminalStatus
            publishStatus()
            persistStatus(terminalStatus)
            logCompletedRun(
                request = request,
                result = lastResult,
                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            )
            return lastResult
        } catch (exception: CancellationException) {
            mutex.withLock {
                if (activeRun === run) activeRun = null
            }
            syncStatus.value = SyncStatus.Idle(lastSuccessfulAt, previousPendingCount)
            publishStatus()
            run.result.cancel(exception)
            throw exception
        } catch (exception: Exception) {
            mutex.withLock {
                if (activeRun === run) activeRun = null
            }
            val failed = SyncResult.Failed(
                exception.message ?: "Synchronization failed",
            )
            val terminalStatus = failed.toStatus(previousPendingCount, lastSuccessfulAt)
            syncStatus.value = terminalStatus
            publishStatus()
            persistStatus(terminalStatus)
            logCompletedRun(
                request = request,
                result = failed,
                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            )
            run.result.complete(failed)
            return failed
        }
    }

    private fun logCompletedRun(
        request: SyncRequest,
        result: SyncResult,
        durationMs: Long,
    ) {
        val completed = result as? SyncResult.Completed
        runCatching {
            analytics?.logEvent(
                SyncAnalyticsEvent.RunCompleted(
                    trigger = request.reason.name,
                    urgency = request.urgency.name,
                    result = result.analyticsName(),
                    durationMs = durationMs,
                    pushedMutationCount = completed?.pushedMutationCount ?: 0,
                    pulledChangeCount = completed?.pulledChangeCount ?: 0,
                    pendingMutationCount = completed?.pendingMutationCount ?: 0,
                    destinationCount = destinations.size,
                    routineDirtyWaitMs = request.routineSchedule?.dirtyWaitMs,
                    routineIntervalMs = request.routineSchedule?.intervalSincePreviousMs,
                    routineForcedByMaximumWait = request.routineSchedule?.forcedByMaximumWait,
                ),
            )
        }
    }

    private fun reportPhase(
        phase: SyncPhase,
        completedItems: Int = 0,
        totalItems: Int? = null,
    ) {
        syncStatus.value = SyncStatus.Running(
            phase = phase,
            completedItems = completedItems,
            totalItems = totalItems,
        )
        publishStatus()
    }

    private fun publishStatus() {
        status.update { mergeTransferStatuses(syncStatus.value, transferStatuses.value) }
    }

    private fun combineResults(
        cloudResult: SyncResult,
        destinationResults: List<SyncResult>,
    ): SyncResult {
        val results = buildList {
            add(cloudResult)
            addAll(destinationResults)
        }
        val failures = results.filterIsInstance<SyncResult.Failed>()
        if (failures.isNotEmpty()) {
            return SyncResult.Failed(
                failures.joinToString(separator = "; ") { failure -> failure.message },
            )
        }

        val offlineResults = results.filterIsInstance<SyncResult.Offline>()
        if (offlineResults.isNotEmpty()) {
            return SyncResult.Offline(
                pendingMutationCount = results.sumOf { result ->
                    when (result) {
                        is SyncResult.Offline -> result.pendingMutationCount
                        is SyncResult.Completed -> result.pendingMutationCount
                        else -> 0
                    }
                },
            )
        }

        val completed = results.filterIsInstance<SyncResult.Completed>()
        if (completed.isNotEmpty()) {
            return SyncResult.Completed(
                pushedMutationCount = completed.sumOf { result -> result.pushedMutationCount },
                pulledChangeCount = completed.sumOf { result -> result.pulledChangeCount },
                pendingMutationCount = completed.sumOf { result -> result.pendingMutationCount },
            )
        }

        return results.first()
    }

    private class ActiveRun(request: SyncRequest) {
        val result = CompletableDeferred<SyncResult>()
        var pendingRequest: SyncRequest? = request
        var activeRequest: SyncRequest? = null
    }
}

private const val DIAGNOSTICS_DESTINATION_ID = "__application_sync_status__"
private const val DIAGNOSTICS_ACCOUNT_ID = "__application__"

private fun SyncResult.toStatus(
    pendingCount: Int,
    lastSuccessfulAt: String?,
): SyncStatus {
    return when (this) {
        is SyncResult.Completed -> SyncStatus.Completed(
            pushedCount = pushedMutationCount,
            pulledCount = pulledChangeCount,
            pendingCount = pendingMutationCount,
            completedAt = Clock.System.now().toString(),
        )
        SyncResult.NotConfigured,
        SyncResult.NotAuthenticated,
        SyncResult.ProfileNotLinked,
        SyncResult.SyncDisabled,
        -> SyncStatus.Disabled
        is SyncResult.Offline -> SyncStatus.Offline(this.pendingMutationCount, lastSuccessfulAt)
        is SyncResult.Failed -> SyncStatus.Failed(
            error = message,
            pendingCount = pendingCount,
            canRetry = true,
            lastSuccessfulAt = lastSuccessfulAt,
        )
    }
}

private fun SyncResult.analyticsName(): String {
    return when (this) {
        is SyncResult.Completed -> "completed"
        SyncResult.NotConfigured -> "not_configured"
        SyncResult.NotAuthenticated -> "not_authenticated"
        SyncResult.ProfileNotLinked -> "profile_not_linked"
        SyncResult.SyncDisabled -> "sync_disabled"
        is SyncResult.Offline -> "offline"
        is SyncResult.Failed -> "failed"
    }
}

private fun SyncCheckpoint.toStatus(): SyncStatus? {
    return when (status) {
        "up_to_date", "pending" -> SyncStatus.Idle(lastSuccessfulAt, pendingMutationCount)
        "offline" -> SyncStatus.Offline(pendingMutationCount, lastSuccessfulAt)
        "failed" -> SyncStatus.Failed(
            error = lastError ?: "Synchronization failed",
            pendingCount = pendingMutationCount,
            canRetry = true,
            lastSuccessfulAt = lastSuccessfulAt,
        )
        "action_required" -> SyncStatus.Disabled
        else -> null
    }
}

private fun SyncStatus.lastSuccessfulAt(): String? {
    return when (this) {
        SyncStatus.Disabled -> null
        is SyncStatus.Idle -> lastSuccessfulAt
        is SyncStatus.Offline -> lastSuccessfulAt
        is SyncStatus.Failed -> lastSuccessfulAt
        is SyncStatus.Completed -> completedAt
        is SyncStatus.Running,
        -> null
    }
}

private fun SyncStatus.pendingCountOrNull(): Int? {
    return when (this) {
        is SyncStatus.Idle -> pendingCount
        is SyncStatus.Offline -> pendingCount
        is SyncStatus.Failed -> pendingCount
        is SyncStatus.Completed -> pendingCount
        else -> null
    }
}

private fun mergeTransferStatuses(
    syncStatus: SyncStatus,
    transferStatuses: List<FileTransferStatus?>,
): SyncStatus {
    val activeTransfers = transferStatuses.filterNotNull().filter { transfer ->
        transfer.activeItems > 0 || transfer.bytesTransferred > 0 || transfer.error != null
    }
    if (activeTransfers.isEmpty()) return syncStatus

    val failures = activeTransfers.filter { transfer -> transfer.error != null }
    if (failures.isNotEmpty()) {
        return SyncStatus.Failed(
            error = failures.mapNotNull { transfer -> transfer.error }.distinct().joinToString("; "),
            pendingCount = activeTransfers.sumOf { transfer -> transfer.activeItems },
            canRetry = failures.all { transfer -> transfer.canRetry },
            lastSuccessfulAt = syncStatus.lastSuccessfulAt(),
        )
    }
    if (syncStatus is SyncStatus.Failed) return syncStatus

    val totalItems = activeTransfers.takeIf { transfers ->
        transfers.all { transfer -> transfer.totalItems != null }
    }?.sumOf { transfer -> transfer.totalItems ?: 0 }
    val totalBytes = activeTransfers.takeIf { transfers ->
        transfers.all { transfer -> transfer.totalBytes != null }
    }?.sumOf { transfer -> transfer.totalBytes ?: 0L }
    val activeItems = activeTransfers.sumOf { transfer -> transfer.activeItems }
    val hasUploads = activeTransfers.any {
        it.phase == SyncPhase.UPLOADING_FILES || it.phase == SyncPhase.TRANSFERRING_FILES
    }
    val hasDownloads = activeTransfers.any {
        it.phase == SyncPhase.DOWNLOADING_FILES || it.phase == SyncPhase.TRANSFERRING_FILES
    }
    val phase = when {
        hasUploads && hasDownloads -> SyncPhase.TRANSFERRING_FILES
        hasUploads -> SyncPhase.UPLOADING_FILES
        hasDownloads -> SyncPhase.DOWNLOADING_FILES
        else -> activeTransfers.first().phase
    }

    return SyncStatus.Running(
        phase = phase,
        completedItems = totalItems?.minus(activeItems)?.coerceAtLeast(0) ?: 0,
        totalItems = totalItems,
        bytesTransferred = activeTransfers.sumOf { transfer -> transfer.bytesTransferred },
        totalBytes = totalBytes,
    )
}

private suspend fun SyncDataRepository.persistStatus(status: SyncStatus) {
    val database = syncCheckpointDatabase ?: return
    val now = Clock.System.now().toString()
    val checkpoint = when (status) {
        SyncStatus.Disabled -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = "action_required",
        )
        is SyncStatus.Idle,
        is SyncStatus.Running,
        -> return
        is SyncStatus.Completed -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = if (status.pendingCount > 0) "pending" else "up_to_date",
            pendingMutationCount = status.pendingCount,
            lastSuccessfulAt = status.completedAt,
        )
        is SyncStatus.Offline -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = "offline",
            pendingMutationCount = status.pendingCount,
            lastSuccessfulAt = status.lastSuccessfulAt,
        )
        is SyncStatus.Failed -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = "failed",
            pendingMutationCount = status.pendingCount,
            lastError = status.error,
            lastSuccessfulAt = status.lastSuccessfulAt,
        )
    }
    try {
        database.saveCheckpoint(checkpoint)
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        // Diagnostics must never turn a completed sync into a failed sync.
    }
}

private fun SyncRequest?.merge(next: SyncRequest): SyncRequest {
    if (this == null) return next
    return SyncRequest(
        reason = next.reason,
        scope = mergeScope(scope, next.scope),
        urgency = if (urgency == com.retro99.sync.domain.SyncUrgency.URGENT ||
            next.urgency == com.retro99.sync.domain.SyncUrgency.URGENT
        ) {
            com.retro99.sync.domain.SyncUrgency.URGENT
        } else {
            com.retro99.sync.domain.SyncUrgency.ROUTINE
        },
        routineSchedule = next.routineSchedule,
    )
}

private fun mergeScope(
    first: com.retro99.sync.domain.SyncScope,
    second: com.retro99.sync.domain.SyncScope,
): com.retro99.sync.domain.SyncScope {
    return when {
        first is com.retro99.sync.domain.SyncScope.All ||
            second is com.retro99.sync.domain.SyncScope.All ->
            com.retro99.sync.domain.SyncScope.All

        first is com.retro99.sync.domain.SyncScope.Books &&
            second is com.retro99.sync.domain.SyncScope.Books ->
            com.retro99.sync.domain.SyncScope.Books(first.bookIds + second.bookIds)

        else -> com.retro99.sync.domain.SyncScope.All
    }
}
