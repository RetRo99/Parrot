package com.retro99.home.ui.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BubblePositionSaveOperationTest {
    @Test
    fun successfulMoveEmitsOneAttemptAndSuccessWithCorrelatedBreadcrumbs() {
        val analytics = RecordingAnalytics()
        var persisted = false

        val succeeded = executeBubblePositionSave(
            analytics = analytics,
            side = BubbleSide.END,
            isRetry = false,
            persist = { persisted = true },
        )

        assertTrue(succeeded)
        assertTrue(persisted)
        assertEquals(
            listOf(
                "continue_reading_bubble_position_attempted",
                "continue_reading_bubble_position_completed",
            ),
            analytics.events.map { it.name },
        )
        assertEquals("end", analytics.events.last().parameters["bubble_side"])
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals(false, analytics.events.last().parameters["is_retry"])
        assertEquals(listOf("started" to "started", "terminal" to "succeeded"), analytics.breadcrumbs.map { it.stage to it.outcome })
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals("bubble_position_save", analytics.breadcrumbs.first().operation)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun failedMoveReportsOnceAndRetryIsAttributedSeparately() {
        val analytics = RecordingAnalytics()
        val failure = IllegalStateException("private storage path")

        val firstSucceeded = executeBubblePositionSave(
            analytics = analytics,
            side = BubbleSide.START,
            isRetry = false,
            persist = { throw failure },
        )
        val retrySucceeded = executeBubblePositionSave(
            analytics = analytics,
            side = BubbleSide.START,
            isRetry = true,
            persist = {},
        )

        assertFalse(firstSucceeded)
        assertTrue(retrySucceeded)
        assertEquals(
            listOf(
                "continue_reading_bubble_position_attempted",
                "continue_reading_bubble_position_completed",
                "continue_reading_bubble_position_attempted",
                "continue_reading_bubble_position_completed",
            ),
            analytics.events.map { it.name },
        )
        assertEquals("failed", analytics.events[1].parameters["outcome"])
        assertEquals("preference_write_failed", analytics.events[1].parameters["reason_code"])
        assertEquals(false, analytics.events[1].parameters["is_retry"])
        assertEquals("succeeded", analytics.events[3].parameters["outcome"])
        assertEquals(true, analytics.events[2].parameters["is_retry"])
        assertEquals(true, analytics.events[3].parameters["is_retry"])
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        val failureContext = analytics.exceptions.single().second
        assertEquals("home", failureContext?.screen)
        assertEquals("floating_bubble", failureContext?.entryPoint)
        assertEquals("bubble_position_save", failureContext?.operation)
        assertEquals("failed", failureContext?.outcome)
        assertEquals("preference_write_failed", failureContext?.reasonCode)
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals(analytics.breadcrumbs[2].correlationId, analytics.breadcrumbs[3].correlationId)
        assertFalse(analytics.breadcrumbs[0].correlationId == analytics.breadcrumbs[2].correlationId)
    }

    @Test
    fun cancellationIsNotReportedAsUnexpectedFailure() {
        val analytics = RecordingAnalytics()
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            executeBubblePositionSave(
                analytics = analytics,
                side = BubbleSide.END,
                isRetry = false,
                persist = { throw cancellation },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(
            listOf(
                "continue_reading_bubble_position_attempted",
                "continue_reading_bubble_position_completed",
            ),
            analytics.events.map { it.name },
        )
        assertEquals("cancelled", analytics.events.last().parameters["outcome"])
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
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
