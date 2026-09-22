package com.retro99.sync.data

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : SyncRepository {
    private val mutex = Mutex()
    private var activeRun: ActiveRun? = null

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
                lastResult = when (execution) {
                    is SyncExecutionResult.Ready -> execution.value
                    SyncExecutionResult.NotConfigured -> SyncResult.NotConfigured
                    SyncExecutionResult.NotAuthenticated -> SyncResult.NotAuthenticated
                    SyncExecutionResult.ProfileNotLinked -> SyncResult.ProfileNotLinked
                    SyncExecutionResult.SyncDisabled -> SyncResult.SyncDisabled
                    is SyncExecutionResult.Failed -> SyncResult.Failed(execution.message)
                }
            }
            run.result.complete(lastResult)
            return lastResult
        } catch (exception: CancellationException) {
            mutex.withLock {
                if (activeRun === run) activeRun = null
            }
            run.result.cancel(exception)
            throw exception
        } catch (exception: Exception) {
            mutex.withLock {
                if (activeRun === run) activeRun = null
            }
            val failed = SyncResult.Failed(
                exception.message ?: "Synchronization failed",
            )
            run.result.complete(failed)
            return failed
        }
    }

    private class ActiveRun(request: SyncRequest) {
        val result = CompletableDeferred<SyncResult>()
        var pendingRequest: SyncRequest? = request
        var activeRequest: SyncRequest? = null
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
