package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogShareOperationTest {
    @Test
    fun emptyLogsAreUnavailableWithoutExceptionOrShareLaunch() {
        val analytics = RecordingLogShareAnalytics()
        var launched = false

        val outcome = executeLogShare(
            analytics = analytics,
            isRetry = false,
            readLogContents = { "" },
            launchShareSheet = { launched = true },
        )

        assertEquals(LogShareOutcome.NoLogs, outcome)
        assertFalse(launched)
        assertEquals(listOf("log_share_attempted", "log_share_unavailable"), analytics.events.map { it.name })
        assertEquals("no_logs", analytics.events.last().parameters["reason_code"])
        assertEquals(listOf("started" to "started", "terminal" to "unavailable"), analytics.breadcrumbs.map { it.stage to it.outcome })
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun successfulShareReportsChooserLaunchRatherThanFileDelivery() {
        val analytics = RecordingLogShareAnalytics()
        var launched = false

        val outcome = executeLogShare(
            analytics = analytics,
            isRetry = true,
            readLogContents = { "non-empty diagnostic log" },
            launchShareSheet = { launched = true },
        )

        assertEquals(LogShareOutcome.Opened, outcome)
        assertTrue(launched)
        assertEquals(listOf("log_share_attempted", "log_share_sheet_launch_succeeded"), analytics.events.map { it.name })
        assertEquals(true, analytics.events.first().parameters["is_retry"])
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals(listOf("started" to "started", "terminal" to "succeeded"), analytics.breadcrumbs.map { it.stage to it.outcome })
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun readFailureIsReportedOnceWithBoundedContext() {
        val analytics = RecordingLogShareAnalytics()
        val failure = IllegalStateException("private file path must not enter context")
        var launched = false

        val outcome = executeLogShare(
            analytics = analytics,
            isRetry = false,
            readLogContents = { throw failure },
            launchShareSheet = { launched = true },
        )

        assertEquals(LogShareOutcome.Failed, outcome)
        assertFalse(launched)
        assertEquals(listOf("log_share_attempted", "log_share_failed"), analytics.events.map { it.name })
        assertEquals("log_read_failed", analytics.events.last().parameters["reason_code"])
        assertEquals("failed", analytics.breadcrumbs.last().outcome)
        assertEquals("log_read_failed", analytics.breadcrumbs.last().reasonCode)
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        assertEquals(analytics.breadcrumbs.last(), analytics.exceptions.single().second)
    }

    @Test
    fun chooserLaunchFailureIsReportedOnceAndRetryIsClassified() {
        val analytics = RecordingLogShareAnalytics()
        val failure = IllegalStateException("platform chooser unavailable")

        val outcome = executeLogShare(
            analytics = analytics,
            isRetry = true,
            readLogContents = { "non-empty diagnostic log" },
            launchShareSheet = { throw failure },
        )

        assertEquals(LogShareOutcome.Failed, outcome)
        assertEquals(listOf("log_share_attempted", "log_share_failed"), analytics.events.map { it.name })
        assertEquals(true, analytics.events.first().parameters["is_retry"])
        assertEquals("share_launch_failed", analytics.events.last().parameters["reason_code"])
        assertEquals("share_launch_failed", analytics.breadcrumbs.last().reasonCode)
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
    }

    @Test
    fun cancellationIsNotReportedAsUnexpectedFailure() {
        val analytics = RecordingLogShareAnalytics()
        val cancellation = CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            executeLogShare(
                analytics = analytics,
                isRetry = false,
                readLogContents = { "non-empty diagnostic log" },
                launchShareSheet = { throw cancellation },
            )
        }

        assertEquals(listOf("log_share_attempted", "log_share_cancelled"), analytics.events.map { it.name })
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
    }
}

private class RecordingLogShareAnalytics : Analytics {
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
