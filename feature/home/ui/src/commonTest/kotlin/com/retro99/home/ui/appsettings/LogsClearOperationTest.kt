package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogsClearOperationTest {
    @Test
    fun successfulClearEmitsOneAttemptAndSuccessWithCorrelatedBreadcrumbs() {
        val analytics = RecordingLogsClearAnalytics()
        var cleared = false

        val succeeded = executeLogsClear(analytics, isRetry = false) { cleared = true }

        assertTrue(succeeded)
        assertTrue(cleared)
        assertEquals(listOf("logs_clear_attempted", "logs_cleared"), analytics.events.map { it.name })
        assertEquals("started", analytics.events.first().parameters["outcome"])
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals(false, analytics.events.last().parameters["is_retry"])
        assertEquals(listOf("started" to "started", "terminal" to "succeeded"), analytics.breadcrumbs.map { it.stage to it.outcome })
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun failedClearReportsExactlyOnceAndRetryIsClassified() {
        val analytics = RecordingLogsClearAnalytics()
        val failure = IllegalStateException("private storage path")

        val firstSucceeded = executeLogsClear(analytics, isRetry = false) { throw failure }
        val retrySucceeded = executeLogsClear(analytics, isRetry = true) {}

        assertFalse(firstSucceeded)
        assertTrue(retrySucceeded)
        assertEquals(
            listOf("logs_clear_attempted", "logs_clear_failed", "logs_clear_attempted", "logs_cleared"),
            analytics.events.map { it.name },
        )
        assertEquals(false, analytics.events[1].parameters["is_retry"])
        assertEquals(true, analytics.events[2].parameters["is_retry"])
        assertEquals(true, analytics.events[3].parameters["is_retry"])
        assertEquals("logs_clear_failed", analytics.events[1].parameters["reason_code"])
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        assertEquals("clear_logs", analytics.exceptions.single().second?.action)
        assertEquals("logs_clear_failed", analytics.exceptions.single().second?.reasonCode)
        assertEquals(4, analytics.breadcrumbs.size)
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals(analytics.breadcrumbs[2].correlationId, analytics.breadcrumbs[3].correlationId)
    }

    @Test
    fun cancellationIsNotReportedAsUnexpectedFailure() {
        val analytics = RecordingLogsClearAnalytics()
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            executeLogsClear(analytics, isRetry = false) { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("logs_clear_attempted", "logs_clear_cancelled"), analytics.events.map { it.name })
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun failureStateAllowsRetryAndSuccessClearsRetryState() {
        val failed = DiagnosticsViewState().withLogsClearOutcome(succeeded = false)
        assertTrue(failed.showLogsClearFailedMessage)
        assertTrue(failed.canRetryLogsClear)
        assertFalse(failed.showLogsClearedMessage)

        val succeeded = failed.withLogsClearOutcome(succeeded = true)
        assertTrue(succeeded.showLogsClearedMessage)
        assertFalse(succeeded.showLogsClearFailedMessage)
        assertFalse(succeeded.canRetryLogsClear)
    }
}

private class RecordingLogsClearAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()
    val breadcrumbs = mutableListOf<DiagnosticContext>()
    val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext?>>()

    override fun logException(throwable: Throwable, message: String?) {
        exceptions += throwable to null
    }

    override fun logException(throwable: Throwable, context: DiagnosticContext) {
        exceptions += throwable to context
    }

    override fun logBreadcrumb(context: DiagnosticContext) {
        breadcrumbs += context
    }

    override fun logEvent(event: AnalyticsEvent) {
        events += event
    }

    override fun setUserId(userId: String?) = Unit
}
