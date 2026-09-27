package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CurrentBookClearOperationTest {
    @Test
    fun successfulClearLogsOneAttemptOneSuccessAndMatchingBreadcrumbs() {
        val analytics = RecordingAnalytics()
        var didClear = false

        val succeeded = executeCurrentBookClear(analytics, isRetry = false) {
            didClear = true
        }

        assertTrue(succeeded)
        assertTrue(didClear)
        assertEquals(
            listOf("current_book_clear_attempted", "current_book_cleared"),
            analytics.events.map { it.name },
        )
        assertEquals(
            listOf("started" to "started", "terminal" to "succeeded"),
            analytics.breadcrumbs.map { it.stage to it.outcome },
        )
        assertEquals(false, analytics.events.first().parameters["is_retry"])
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals(false, analytics.events.last().parameters["is_retry"])
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun failedClearReportsOnceKeepsFailureContextAndCanBeRetried() {
        val analytics = RecordingAnalytics()
        val failure = IllegalStateException("private storage details")

        val firstAttemptSucceeded = executeCurrentBookClear(analytics, isRetry = false) {
            throw failure
        }
        val retrySucceeded = executeCurrentBookClear(analytics, isRetry = true) {}

        assertFalse(firstAttemptSucceeded)
        assertTrue(retrySucceeded)
        assertEquals(
            listOf(
                "current_book_clear_attempted",
                "current_book_clear_failed",
                "current_book_clear_attempted",
                "current_book_cleared",
            ),
            analytics.events.map { it.name },
        )
        assertEquals(false, analytics.events[0].parameters["is_retry"])
        assertEquals(false, analytics.events[1].parameters["is_retry"])
        assertEquals(true, analytics.events[2].parameters["is_retry"])
        assertEquals("succeeded", analytics.events[3].parameters["outcome"])
        assertEquals(true, analytics.events[3].parameters["is_retry"])
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        val failureContext = assertNotNull(analytics.exceptions.single().second)
        assertEquals("app_settings", failureContext.screen)
        assertEquals("clear_current_book", failureContext.action)
        assertEquals("terminal", failureContext.stage)
        assertEquals("failed", failureContext.outcome)
        assertEquals("current_book_clear_failed", failureContext.reasonCode)
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals(analytics.breadcrumbs[2].correlationId, analytics.breadcrumbs[3].correlationId)
    }

    @Test
    fun cancellationIsNotReportedAsFailureAndPropagates() {
        val analytics = RecordingAnalytics()
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            executeCurrentBookClear(analytics, isRetry = false) { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("current_book_clear_attempted"), analytics.events.map { it.name })
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun failedStatePreservesCurrentBookAndSuccessfulStateClearsIt() {
        val withCurrentBook = AppSettingsViewState(hasCurrentlyReadingBook = true)

        val failed = withCurrentBook.withCurrentBookClearOutcome(succeeded = false)
        assertTrue(failed.hasCurrentlyReadingBook)
        assertTrue(failed.showCurrentBookClearFailedMessage)
        assertTrue(failed.canRetryCurrentBookClear)

        val succeeded = failed.withCurrentBookClearOutcome(succeeded = true)
        assertFalse(succeeded.hasCurrentlyReadingBook)
        assertFalse(succeeded.showCurrentBookClearFailedMessage)
        assertFalse(succeeded.canRetryCurrentBookClear)
        assertTrue(succeeded.showCurrentBookClearedMessage)
    }
}

private class RecordingAnalytics : Analytics {
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
