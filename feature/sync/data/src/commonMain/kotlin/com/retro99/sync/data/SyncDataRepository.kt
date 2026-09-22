package com.retro99.sync.data

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncActionRequired
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : SyncRepository {
    private val mutex = Mutex()
    private var activeRun: ActiveRun? = null
    private val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

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

        status.value = SyncStatus.Synchronizing(request)

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
            status.value = lastResult.toStatus()
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
            status.value = failed.toStatus()
            run.result.complete(failed)
            return failed
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

private fun SyncResult.toStatus(): SyncStatus {
    return when (this) {
        is SyncResult.Completed -> {
            if (pendingMutationCount > 0) {
                SyncStatus.Pending(pendingMutationCount)
            } else {
                SyncStatus.UpToDate
            }
        }
        SyncResult.NotConfigured -> SyncStatus.ActionRequired(SyncActionRequired.NOT_CONFIGURED)
        SyncResult.NotAuthenticated -> SyncStatus.ActionRequired(SyncActionRequired.NOT_AUTHENTICATED)
        SyncResult.ProfileNotLinked -> SyncStatus.ActionRequired(SyncActionRequired.PROFILE_NOT_LINKED)
        SyncResult.SyncDisabled -> SyncStatus.ActionRequired(SyncActionRequired.SYNC_DISABLED)
        is SyncResult.Failed -> SyncStatus.Failed(message)
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
