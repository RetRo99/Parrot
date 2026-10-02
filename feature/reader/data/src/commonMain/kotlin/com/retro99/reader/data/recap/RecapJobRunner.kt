package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapInput
import com.retro99.reader.domain.recap.RecapJobPolicy
import com.retro99.reader.domain.recap.RecapResult
import com.retro99.reader.domain.recap.RecapStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** Why the runner was woken; for diagnostics only. */
enum class RecapTrigger {
    APP_START,
    FOREGROUND,
    CONNECTIVITY,
    SIGN_IN,
    SESSION_ENDED,
    USER_RETRY,
    SCHEDULED,
    BACKGROUND_WORK,
}

/**
 * Sends pending recaps, one at a time, from an app-scoped coroutine.
 * Rows are claimed with a conditional update before any request, so a
 * row is never sent twice concurrently. Viewing a recap never calls this.
 */
class RecapJobRunner(
    private val database: SessionRecapDatabase,
    private val selector: RecapEngineSelector,
    private val diagnostics: RecapDiagnostics,
    private val clock: Clock = Clock.System,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    // The database and token follow the active profile; a pass must not
    // span a switch.
    private val activeProfileId: () -> String? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val wakeups = Channel<RecapTrigger>(Channel.CONFLATED)
    private val passMutex = Mutex()
    private var started = false
    private var startupDone = false
    private var lastCleanupAt = Long.MIN_VALUE / 2
    private var startupWork: suspend () -> Unit = {}
    private var timer: Job? = null

    // Runner-wide pauses: a quota, outage or auth answer applies to every
    // row, so no other row is sent until they end.
    private var blockedUntil = 0L
    private var authBlockedUntil = 0L
    private var authFailures = 0

    /**
     * Starts the loop and listens for sign-in. [startupWork] and retention
     * run before the first pass that can reach the database.
     */
    fun start(startupWork: suspend () -> Unit = {}) {
        if (started) return
        started = true
        this.startupWork = startupWork
        scope.launch {
            for (trigger in wakeups) {
                try {
                    runPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // e.g. no profile database yet at app start; next trigger retries.
                    diagnostics.breadcrumb(stage = "pass", outcome = "failed", reasonCode = e::class.simpleName)
                }
            }
        }
        // Consent turned on or a user signed in: send what is waiting.
        selector.observeAvailable()
            .filter { available -> available }
            .onEach { onEngineAvailable() }
            .launchIn(scope)
        trigger(RecapTrigger.APP_START)
    }

    /** Sign-in or consent turned on: a new session may fix auth. */
    internal fun onEngineAvailable() {
        authBlockedUntil = 0L
        authFailures = 0
        trigger(RecapTrigger.SIGN_IN)
    }

    /** Non-blocking; repeated triggers collapse into one pass. */
    fun trigger(reason: RecapTrigger) {
        wakeups.trySend(reason)
    }

    /** One pass over due rows. Safe to call from background work too. */
    suspend fun runPending(): Int = passMutex.withLock {
        if (!startupDone) {
            startupWork()
            runCleanup()
            startupDone = true
        }
        val now = clock.now().toEpochMilliseconds()
        if (now - lastCleanupAt >= RecapJobPolicy.CLEANUP_INTERVAL.inWholeMilliseconds) runCleanup()
        database.recoverStaleRunning(
            staleBefore = now - RecapJobPolicy.STALE_RUNNING_AFTER.inWholeMilliseconds,
            now = now,
            maxAttempts = RecapJobPolicy.MAX_ATTEMPTS,
        )
        val profile = activeProfileId()
        var sent = 0
        var guard = 0
        while (guard++ < MAX_ROWS_PER_PASS) {
            if (clock.now().toEpochMilliseconds() < blockEnd()) break
            val engine = selector.select() ?: break
            val row = database.getNextDue(clock.now().toEpochMilliseconds()) ?: break
            val excerpt = row.excerpt ?: break
            if (!database.claim(row.sessionId, engine.id, clock.now().toEpochMilliseconds())) {
                continue
            }
            // Never send one profile's text with another's token; the row
            // is recovered as stale in its own profile later.
            if (profileChanged(profile)) break
            sent++
            val result = try {
                engine.generate(RecapInput(excerpt, row.language, row.lastSentence))
            } catch (e: CancellationException) {
                // e.g. WorkManager stopped the worker: back off instead of
                // leaving the row RUNNING until stale recovery.
                withContext(NonCancellable) {
                    if (!profileChanged(profile)) {
                        record(row, attempt = row.attemptCount + 1, RecapResult.Retryable(RecapErrorCode.NETWORK))
                        scheduleNext()
                    }
                }
                throw e
            } catch (e: Exception) {
                diagnostics.failure(e, stage = "generate")
                RecapResult.Retryable(RecapErrorCode.UNKNOWN)
            }
            // The result belongs to a database that is no longer open.
            if (profileChanged(profile)) break
            if (!record(row, attempt = row.attemptCount + 1, result)) break
        }
        scheduleNext()
        sent
    }

    /** Retention: at app start, then at most hourly before a pass. */
    suspend fun runCleanup() {
        try {
            val now = clock.now().toEpochMilliseconds()
            lastCleanupAt = now
            database.applyRetention(
                excerptCutoff = now - RecapJobPolicy.EXCERPT_RETENTION.inWholeMilliseconds,
                rowCutoff = now - RecapJobPolicy.ROW_RETENTION.inWholeMilliseconds,
                now = now,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.failure(e, stage = "retention")
        }
    }

    /** Stores [result]; returns false when the pass should stop. */
    private suspend fun record(row: SessionRecapEntity, attempt: Int, result: RecapResult): Boolean {
        val now = clock.now().toEpochMilliseconds()
        val id = row.sessionId
        var stored = true
        val keepGoing = when (result) {
            is RecapResult.Success -> {
                stored = database.complete(id, RecapStatus.SUCCEEDED.name, result.summary, result.model, now)
                authFailures = 0
                true
            }
            RecapResult.NotEnough -> {
                stored = database.complete(id, RecapStatus.NOT_ENOUGH.name, null, null, now)
                authFailures = 0
                true
            }
            is RecapResult.Permanent -> {
                stored = database.fail(
                    id, RecapStatus.FAILED_PERMANENT.name, attempt, null, result.code.name, now,
                    // Rejected input can't be retried; keep no text for it.
                    dropText = result.code.isInputError,
                )
                true
            }
            is RecapResult.Retryable -> {
                val next = RecapJobPolicy.nextAttemptAt(now, attempt, result.retryAfter)
                val capped = RecapJobPolicy.countsTowardCap(result.code) &&
                    attempt >= RecapJobPolicy.MAX_ATTEMPTS
                stored = if (capped) {
                    // The time is kept so a user retry still honours it.
                    database.fail(id, RecapStatus.FAILED_PERMANENT.name, attempt, next, result.code.name, now)
                } else {
                    database.fail(id, RecapStatus.FAILED_RETRYABLE.name, attempt, next, result.code.name, now)
                }
                // Quota, outage or network: the next row would fail the same way.
                blockedUntil = maxOf(blockedUntil, next)
                false
            }
            RecapResult.AuthRequired -> {
                // Not billed: undo the attempt. Back off, since the server
                // can keep refusing a session the client thinks is valid.
                stored = database.fail(
                    id, RecapStatus.PENDING.name, attempt - 1, null, RecapErrorCode.AUTH_REQUIRED.name, now,
                )
                authFailures++
                authBlockedUntil = now + RecapJobPolicy.backoff(authFailures).inWholeMilliseconds
                false
            }
        }
        diagnostics.breadcrumb(stage = "result", outcome = result.outcomeName(), reasonCode = result.code()?.name)
        if (!stored) {
            // The row changed under us (e.g. deleted); stop rather than guess.
            diagnostics.breadcrumb(stage = "result", outcome = "not_stored", reasonCode = null)
            return false
        }
        return keepGoing
    }

    private fun profileChanged(profile: String?): Boolean {
        if (activeProfileId() == profile) return false
        diagnostics.breadcrumb(stage = "pass", outcome = "profile_changed", reasonCode = null)
        return true
    }

    private suspend fun scheduleNext() {
        timer?.cancel()
        val now = clock.now().toEpochMilliseconds()
        // A row left RUNNING by a dead process is recovered once it is stale.
        val staleAt = database.getOldestRunningUpdate()
            ?.let { it + RecapJobPolicy.STALE_RUNNING_AFTER.inWholeMilliseconds + 1 }
        val block = blockEnd().takeIf { it > now }
        val at = listOfNotNull(database.getEarliestScheduled(now), staleAt, block).minOrNull()
            ?.coerceAtLeast(block ?: 0L)
            ?: return
        timer = scope.launch {
            delay(at - now)
            trigger(RecapTrigger.SCHEDULED)
        }
    }

    private fun blockEnd(): Long = maxOf(blockedUntil, authBlockedUntil)

    private fun RecapResult.outcomeName(): String = when (this) {
        is RecapResult.Success -> "succeeded"
        RecapResult.NotEnough -> "not_enough"
        is RecapResult.Retryable -> "retryable"
        is RecapResult.Permanent -> "permanent"
        RecapResult.AuthRequired -> "auth_required"
    }

    private fun RecapResult.code(): RecapErrorCode? = when (this) {
        is RecapResult.Retryable -> code
        is RecapResult.Permanent -> code
        else -> null
    }

    private companion object {
        /** Bounds one pass even if a claim keeps losing a race. */
        const val MAX_ROWS_PER_PASS = 20
    }
}
