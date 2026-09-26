package com.retro99.sync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coalesces dirty progress notifications into bounded routine sync requests.
 *
 * Local persistence is intentionally outside this class. A caller marks the
 * scheduler dirty only after its local save succeeds; remote work is then
 * delayed until the position is idle, rate-limited, or reaches the maximum
 * dirty wait.
 */
class RoutineSyncScheduler(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val requestSync: suspend (RoutineSyncSchedule) -> Unit,
) : AutoCloseable {
    private val dirtySignals = Channel<Long>(Channel.CONFLATED)
    private val worker: Job = scope.launch {
        runWorker()
    }

    fun markDirty() {
        dirtySignals.trySend(nowMillis())
    }

    override fun close() {
        // Idempotent: callers close this both from the reader and from onCleared().
        // Closing wakes a parked receiveCatching() with a closed result instead of
        // throwing ClosedReceiveChannelException into the caller's coroutine.
        dirtySignals.close()
        worker.cancel()
    }

    private suspend fun runWorker() {
        var dirtySince: Long? = null
        var lastDirtyAt: Long? = null
        var lastSyncAt: Long? = null

        while (currentCoroutineContext().isActive) {
            if (dirtySince == null) {
                val firstDirtyAt = dirtySignals.receiveCatching().getOrNull() ?: return
                dirtySince = firstDirtyAt
                lastDirtyAt = firstDirtyAt
            }

            val now = nowMillis()
            val dirtyStart = dirtySince
            val idleDeadline = (lastDirtyAt ?: dirtyStart) + IDLE_DEBOUNCE_MS
            val rateLimitDeadline = lastSyncAt?.let { it + MIN_INTERVAL_MS } ?: Long.MIN_VALUE
            val maximumWaitDeadline = dirtyStart + MAX_DIRTY_WAIT_MS
            val nextDeadline = minOf(
                maxOf(idleDeadline, rateLimitDeadline),
                maximumWaitDeadline,
            )
            val waitMillis = nextDeadline - now

            if (waitMillis > 0L) {
                // receiveCatching() rather than receive(): a close() arriving during this
                // wait must stop the worker cleanly instead of throwing into its scope.
                val received = withTimeoutOrNull(waitMillis) {
                    dirtySignals.receiveCatching()
                }
                if (received != null) {
                    val newerDirtyAt = received.getOrNull() ?: return
                    lastDirtyAt = newerDirtyAt
                    continue
                }
            }

            dirtySince = null
            val syncNow = nowMillis()
            val intervalSincePreviousMs = lastSyncAt?.let { syncNow - it }
            val idleOrRateDeadline = maxOf(idleDeadline, rateLimitDeadline)
            val forcedByMaximumWait = syncNow >= maximumWaitDeadline &&
                maximumWaitDeadline <= idleOrRateDeadline
            lastSyncAt = syncNow
            requestSync(
                RoutineSyncSchedule(
                    dirtyWaitMs = syncNow - dirtyStart,
                    intervalSincePreviousMs = intervalSincePreviousMs,
                    forcedByMaximumWait = forcedByMaximumWait,
                ),
            )
        }
    }

    companion object {
        const val IDLE_DEBOUNCE_MS = 3_000L
        const val MIN_INTERVAL_MS = 15_000L
        const val MAX_DIRTY_WAIT_MS = 30_000L
    }
}
