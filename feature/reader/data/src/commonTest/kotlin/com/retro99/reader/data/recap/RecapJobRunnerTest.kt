package com.retro99.reader.data.recap

import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapJobPolicy
import com.retro99.reader.domain.recap.RecapResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RecapJobRunnerTest {

    private val clock = TestClock()
    private val database = FakeSessionRecapDatabase()
    private val engine = FakeRecapEngine()
    private val selector = FakeRecapSelector(engine)
    private val analytics = RecordingAnalytics()

    private fun runner() = RecapJobRunner(
        database = database,
        selector = selector,
        diagnostics = RecapDiagnostics(analytics),
        clock = clock,
        dispatcher = StandardTestDispatcher(),
    )

    @Test
    fun successStoresSummaryAndModelAndDropsTheExcerpt() = runTest {
        database.put(pendingRow("s1"))

        assertEquals(1, runner().runPending())

        val row = database["s1"]!!
        assertEquals("SUCCEEDED", row.status)
        assertEquals("A summary.", row.summary)
        assertEquals("hy3", row.model)
        assertNull(row.excerpt)
        assertNull(row.lastSentence)
        assertEquals(1, row.attemptCount)
        assertEquals("sl", engine.inputs.single().language)
        assertEquals("She stopped here.", engine.inputs.single().lastSentence)
    }

    @Test
    fun runsOneRowAtATimeOldestFirst() = runTest {
        database.put(pendingRow("new", createdAt = 2))
        database.put(pendingRow("old", createdAt = 1))
        val order = mutableListOf<String>()
        engine.next = { input ->
            order += database.rows.value.values.single { it.status == "RUNNING" }.sessionId
            RecapResult.NotEnough
        }

        assertEquals(2, runner().runPending())

        assertEquals(listOf("old", "new"), order)
        assertEquals("NOT_ENOUGH", database["old"]!!.status)
    }

    @Test
    fun concurrentPassesNeverSendARowTwice() = runTest {
        database.put(pendingRow("s1"))
        val gate = CompletableDeferred<Unit>()
        engine.next = {
            gate.await()
            RecapResult.Success("Done.", null)
        }
        val runner = runner()

        val first = async { runner.runPending() }
        val second = async { runner.runPending() }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(1, first.await() + second.await())
        assertEquals(1, engine.inputs.size)
        assertEquals(1, database["s1"]!!.attemptCount)
    }

    @Test
    fun staleRunningRowsAreRecoveredAndRetried() = runTest {
        val staleAt = clock.nowMs - RecapJobPolicy.STALE_RUNNING_AFTER.inWholeMilliseconds - 1
        database.put(pendingRow("dead", status = "RUNNING", attemptCount = 1).copy(updatedAt = staleAt))
        database.put(pendingRow("busy", status = "RUNNING", attemptCount = 1).copy(updatedAt = clock.nowMs))

        runner().runPending()

        assertEquals("SUCCEEDED", database["dead"]!!.status)
        assertEquals(2, database["dead"]!!.attemptCount)
        assertEquals("RUNNING", database["busy"]!!.status)
    }

    @Test
    fun retryableFailureBacksOffAndStopsThePass() = runTest {
        database.put(pendingRow("s1", createdAt = 1))
        database.put(pendingRow("s2", createdAt = 2))
        engine.next = { RecapResult.Retryable(RecapErrorCode.PROVIDER_ERROR) }

        assertEquals(1, runner().runPending())

        val row = database["s1"]!!
        assertEquals("FAILED_RETRYABLE", row.status)
        assertEquals("PROVIDER_ERROR", row.lastError)
        assertEquals(clock.nowMs + RecapJobPolicy.backoff(1).inWholeMilliseconds, row.nextAttemptAt)
        assertEquals("PENDING", database["s2"]!!.status)
    }

    @Test
    fun retryAfterIsHonouredWhenLongerThanBackoff() = runTest {
        database.put(pendingRow("s1"))
        engine.next = { RecapResult.Retryable(RecapErrorCode.RATE_LIMITED, retryAfter = 5.hours) }
        val runner = runner()

        runner.runPending()
        assertEquals(clock.nowMs + 5.hours.inWholeMilliseconds, database["s1"]!!.nextAttemptAt)

        // Not due yet: nothing is sent.
        clock.nowMs += 1.hours.inWholeMilliseconds
        assertEquals(0, runner.runPending())

        clock.nowMs += 4.hours.inWholeMilliseconds
        engine.next = { RecapResult.Success("Later.", null) }
        assertEquals(1, runner.runPending())
        assertEquals("SUCCEEDED", database["s1"]!!.status)
    }

    @Test
    fun givesUpAfterMaxAttempts() = runTest {
        database.put(pendingRow("s1", attemptCount = RecapJobPolicy.MAX_ATTEMPTS - 1))
        engine.next = { RecapResult.Retryable(RecapErrorCode.TIMEOUT, 1.minutes) }

        runner().runPending()

        val row = database["s1"]!!
        assertEquals("FAILED_PERMANENT", row.status)
        assertEquals(RecapJobPolicy.MAX_ATTEMPTS, row.attemptCount)
        assertNull(row.nextAttemptAt)
    }

    @Test
    fun permanentFailureIsNotRetried() = runTest {
        database.put(pendingRow("s1"))
        engine.next = { RecapResult.Permanent(RecapErrorCode.UNSUPPORTED_LANGUAGE) }
        val runner = runner()

        runner.runPending()
        clock.nowMs += 10.hours.inWholeMilliseconds
        runner.runPending()

        assertEquals("FAILED_PERMANENT", database["s1"]!!.status)
        assertEquals(1, engine.inputs.size)
    }

    @Test
    fun authRequiredLeavesTheRowPendingWithoutCountingTheAttempt() = runTest {
        database.put(pendingRow("s1"))
        engine.next = { RecapResult.AuthRequired }

        runner().runPending()

        val row = database["s1"]!!
        assertEquals("PENDING", row.status)
        assertEquals(0, row.attemptCount)
        assertEquals("AUTH_REQUIRED", row.lastError)
        assertEquals(row.excerpt, pendingRow("s1").excerpt)
    }

    @Test
    fun nothingIsSentWithoutAnEngine() = runTest {
        database.put(pendingRow("s1"))
        selector.engine = null

        assertEquals(0, runner().runPending())
        assertEquals("PENDING", database["s1"]!!.status)
        assertEquals(0, engine.inputs.size)
    }

    @Test
    fun anEngineCrashIsRetryable() = runTest {
        database.put(pendingRow("s1"))
        engine.next = { error("boom") }

        runner().runPending()

        assertEquals("FAILED_RETRYABLE", database["s1"]!!.status)
        assertEquals("UNKNOWN", database["s1"]!!.lastError)
    }

    @Test
    fun startupWorkRunsBeforeTheFirstPassAndRetriesAfterAFailure() = runTest {
        var calls = 0
        val runner = runner()
        runner.start(startupWork = {
            calls++
            if (calls == 1) error("no profile database yet")
        })
        database.put(pendingRow("s1"))

        kotlin.test.assertFails { runner.runPending() }
        assertEquals("PENDING", database["s1"]!!.status)

        assertEquals(1, runner.runPending())
        runner.runPending()
        assertEquals(2, calls)
        assertEquals(1, database.retentionCalls.size)
    }

    @Test
    fun cleanupAppliesTheRetentionWindows() = runTest {
        runner().runCleanup()

        val (excerptCutoff, rowCutoff, now) = database.retentionCalls.single()
        assertEquals(clock.nowMs, now)
        assertEquals(clock.nowMs - RecapJobPolicy.EXCERPT_RETENTION.inWholeMilliseconds, excerptCutoff)
        assertEquals(clock.nowMs - RecapJobPolicy.ROW_RETENTION.inWholeMilliseconds, rowCutoff)
    }

    @Test
    fun breadcrumbsNeverCarryText() = runTest {
        database.put(pendingRow("s1"))
        runner().runPending()

        val text = analytics.breadcrumbs.joinToString { it.toString() }
        assertEquals(false, text.contains("Read text"))
        assertEquals(false, text.contains("A summary."))
    }
}
