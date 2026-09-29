package com.retro99.home.ui.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.ContinueReadingEntryPoint
import com.retro99.analytics.api.ContinueReadingMediaType
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.analytics.api.beginContinueReadingOpen
import com.retro99.analytics.api.completeContinueReadingOpen
import com.retro99.analytics.api.continueReadingOpenOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContinueReadingOpenTelemetryTest {
    @Test
    fun successHasOneSourceAttributedAttemptAndTerminalBreadcrumbs() {
        val analytics = RecordingOpenAnalytics()
        val operation = analytics.beginContinueReadingOpen(
            entryPoint = ContinueReadingEntryPoint.FloatingBubble,
            mediaType = ContinueReadingMediaType.ReadAloud,
        )

        analytics.completeContinueReadingOpen(
            operation = operation,
            outcome = NavigationAnalyticsEvent.ContinueReadingOpenOutcome.Succeeded,
        )

        assertEquals(
            listOf("continue_reading_open_attempted", "continue_reading_open_completed"),
            analytics.events.map { it.name },
        )
        assertEquals("floating_bubble", analytics.events.first().parameters["entry_point"])
        assertEquals("readaloud", analytics.events.first().parameters["media_type"])
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals(listOf("started" to "started", "terminal" to "succeeded"), analytics.breadcrumbs.map { it.stage to it.outcome })
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals("continue_reading_open", analytics.breadcrumbs.first().operation)
        assertFalse(analytics.events.any { "book_uuid" in it.parameters || "book_title" in it.parameters })
    }

    @Test
    fun failureAndRetryHaveSeparateAttemptsAndCorrelationIds() {
        val analytics = RecordingOpenAnalytics()
        val first = analytics.beginContinueReadingOpen(
            entryPoint = ContinueReadingEntryPoint.Shelf,
            mediaType = ContinueReadingMediaType.Ebook,
        )
        analytics.completeContinueReadingOpen(
            first,
            NavigationAnalyticsEvent.ContinueReadingOpenOutcome.Failed,
            NavigationAnalyticsEvent.ContinueReadingOpenReasonCode.NetworkUnavailable,
        )
        val retry = analytics.beginContinueReadingOpen(
            entryPoint = first.entryPoint,
            mediaType = first.mediaType,
            isRetry = true,
        )
        analytics.completeContinueReadingOpen(
            retry,
            NavigationAnalyticsEvent.ContinueReadingOpenOutcome.Succeeded,
        )

        assertEquals(
            listOf(
                "continue_reading_open_attempted",
                "continue_reading_open_completed",
                "continue_reading_open_attempted",
                "continue_reading_open_completed",
            ),
            analytics.events.map { it.name },
        )
        assertEquals("failed", analytics.events[1].parameters["outcome"])
        assertEquals("network_unavailable", analytics.events[1].parameters["reason_code"])
        assertEquals(true, analytics.events[2].parameters["is_retry"])
        assertEquals("succeeded", analytics.events[3].parameters["outcome"])
        assertNotEquals(first.correlationId, retry.correlationId)
        assertEquals(first.correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals(retry.correlationId, analytics.breadcrumbs[2].correlationId)
    }

    @Test
    fun conflictCancellationHasAnExplicitNonFailureTerminal() {
        val analytics = RecordingOpenAnalytics()
        val operation = analytics.beginContinueReadingOpen(
            entryPoint = ContinueReadingEntryPoint.Shelf,
            mediaType = ContinueReadingMediaType.Audiobook,
        )

        analytics.completeContinueReadingOpen(
            operation = operation,
            outcome = NavigationAnalyticsEvent.ContinueReadingOpenOutcome.Cancelled,
            reasonCode = NavigationAnalyticsEvent.ContinueReadingOpenReasonCode.PlaybackConflictDismissed,
        )

        assertEquals("cancelled", analytics.events.last().parameters["outcome"])
        assertEquals("playback_conflict_dismissed", analytics.events.last().parameters["reason_code"])
        assertEquals("home", analytics.breadcrumbs.last().screen)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun restoredOperationRejectsUnboundedRouteValues() {
        assertTrue(
            continueReadingOpenOperation(
                entryPoint = "floating_bubble",
                mediaType = "readaloud",
                correlationId = "123e4567-e89b-12d3-a456-426614174000",
            ) != null,
        )
        assertEquals(
            null,
            continueReadingOpenOperation(
                entryPoint = "private-source",
                mediaType = "readaloud",
                correlationId = "123e4567-e89b-12d3-a456-426614174000",
            ),
        )
        assertEquals(
            null,
            continueReadingOpenOperation(
                entryPoint = "floating_bubble",
                mediaType = "private_type",
                correlationId = "123e4567-e89b-12d3-a456-426614174000",
            ),
        )
        assertEquals(
            null,
            continueReadingOpenOperation(
                entryPoint = "floating_bubble",
                mediaType = "readaloud",
                correlationId = "private-correlation",
            ),
        )
    }
}

private class RecordingOpenAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()
    val breadcrumbs = mutableListOf<DiagnosticContext>()
    val exceptions = mutableListOf<Throwable>()

    override fun logException(throwable: Throwable, message: String?) {
        exceptions += throwable
    }

    override fun logBreadcrumb(context: DiagnosticContext) {
        breadcrumbs += context
    }

    override fun logEvent(event: AnalyticsEvent) {
        events += event
    }

    override fun setUserId(userId: String?) = Unit
}
