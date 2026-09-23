package com.retro99.sync.data

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncActionRequired
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
    @Provided internal val syncCheckpointDatabase: SyncCheckpointDatabase? = null,
    @Provided private val analytics: Analytics? = null,
) : SyncRepository {
    private val mutex = Mutex()
    private var activeRun: ActiveRun? = null
    private val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diagnosticsLoadJob: Job = diagnosticsScope.launch {
        val checkpoint = syncCheckpointDatabase?.getCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
        )
        checkpoint?.toStatus()?.let { persistedStatus ->
            status.value = persistedStatus
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
        val previousStatus = status.value
        status.value = SyncStatus.Synchronizing(request, previousStatus.lastSuccessfulAt())
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
                    syncPass.execute(nextRequest, context)
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
                        destination.execute(nextRequest)
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
            val terminalStatus = lastResult.toStatus(status.value.lastSuccessfulAt())
            status.value = terminalStatus
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
            status.value = SyncStatus.Idle
            run.result.cancel(exception)
            throw exception
        } catch (exception: Exception) {
            mutex.withLock {
                if (activeRun === run) activeRun = null
            }
            val failed = SyncResult.Failed(
                exception.message ?: "Synchronization failed",
            )
            val terminalStatus = failed.toStatus(status.value.lastSuccessfulAt())
            status.value = terminalStatus
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
                ),
            )
        }
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

private fun SyncResult.toStatus(lastSuccessfulAt: String?): SyncStatus {
    return when (this) {
        is SyncResult.Completed -> {
            val successfulAt = Clock.System.now().toString()
            if (pendingMutationCount > 0) {
                SyncStatus.Pending(pendingMutationCount, successfulAt)
            } else {
                SyncStatus.UpToDate(successfulAt)
            }
        }
        SyncResult.NotConfigured -> SyncStatus.ActionRequired(SyncActionRequired.NOT_CONFIGURED, lastSuccessfulAt)
        SyncResult.NotAuthenticated -> SyncStatus.ActionRequired(SyncActionRequired.NOT_AUTHENTICATED, lastSuccessfulAt)
        SyncResult.ProfileNotLinked -> SyncStatus.ActionRequired(SyncActionRequired.PROFILE_NOT_LINKED, lastSuccessfulAt)
        SyncResult.SyncDisabled -> SyncStatus.ActionRequired(SyncActionRequired.SYNC_DISABLED, lastSuccessfulAt)
        is SyncResult.Failed -> SyncStatus.Failed(message, lastSuccessfulAt)
    }
}

private fun SyncResult.analyticsName(): String {
    return when (this) {
        is SyncResult.Completed -> "completed"
        SyncResult.NotConfigured -> "not_configured"
        SyncResult.NotAuthenticated -> "not_authenticated"
        SyncResult.ProfileNotLinked -> "profile_not_linked"
        SyncResult.SyncDisabled -> "sync_disabled"
        is SyncResult.Failed -> "failed"
    }
}

private fun SyncCheckpoint.toStatus(): SyncStatus? {
    return when (status) {
        "up_to_date" -> SyncStatus.UpToDate(lastSuccessfulAt)
        "pending" -> SyncStatus.Pending(pendingMutationCount, lastSuccessfulAt)
        "failed" -> SyncStatus.Failed(lastError ?: "Synchronization failed", lastSuccessfulAt)
        "action_required" -> SyncStatus.ActionRequired(
            reason = lastError?.let { reason ->
                runCatching { SyncActionRequired.valueOf(reason) }.getOrNull()
            } ?: SyncActionRequired.NOT_AUTHENTICATED,
            lastSuccessfulAt = lastSuccessfulAt,
        )
        else -> null
    }
}

private fun SyncStatus.lastSuccessfulAt(): String? {
    return when (this) {
        SyncStatus.Idle -> null
        is SyncStatus.Synchronizing -> lastSuccessfulAt
        is SyncStatus.UpToDate -> lastSuccessfulAt
        is SyncStatus.Pending -> lastSuccessfulAt
        is SyncStatus.ActionRequired -> lastSuccessfulAt
        is SyncStatus.Failed -> lastSuccessfulAt
    }
}

private suspend fun SyncDataRepository.persistStatus(status: SyncStatus) {
    val database = syncCheckpointDatabase ?: return
    val now = Clock.System.now().toString()
    val checkpoint = when (status) {
        SyncStatus.Idle,
        is SyncStatus.Synchronizing,
        -> return
        is SyncStatus.UpToDate -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = "up_to_date",
            lastSuccessfulAt = status.lastSuccessfulAt,
        )
        is SyncStatus.Pending -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = "pending",
            pendingMutationCount = status.pendingMutationCount,
            lastSuccessfulAt = status.lastSuccessfulAt,
        )
        is SyncStatus.ActionRequired -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = "action_required",
            lastSuccessfulAt = status.lastSuccessfulAt,
            lastError = status.reason.name,
        )
        is SyncStatus.Failed -> SyncCheckpoint(
            destinationId = DIAGNOSTICS_DESTINATION_ID,
            remoteAccountId = DIAGNOSTICS_ACCOUNT_ID,
            cursor = null,
            updatedAt = now,
            status = "failed",
            lastSuccessfulAt = status.lastSuccessfulAt,
            lastError = status.message,
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
