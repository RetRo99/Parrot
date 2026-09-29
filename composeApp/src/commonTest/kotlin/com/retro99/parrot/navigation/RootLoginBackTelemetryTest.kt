package com.retro99.parrot.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootLoginBackTelemetryTest {
    @Test
    fun appliedLoginBackRecordsOneCorrelatedAttemptAndSuccess() {
        val analytics = RecordingAnalytics()
        val context = RootLoginBackContext(
            sourceScreen = "login",
            destinationScreen = "server_management",
            entryPoint = "system_back",
            correlationId = "qa-correlation",
        )

        logRootLoginBackAttempt(analytics, context)
        logRootLoginBackCompleted(analytics, context, applied = true)

        assertEquals(
            listOf("navigation_back_attempted", "navigation_back"),
            analytics.events.map { it.name },
        )
        assertEquals("login", analytics.events.first().parameters["source_screen"])
        assertEquals("server_management", analytics.events.first().parameters["destination_screen"])
        assertEquals("system_back", analytics.events.first().parameters["entry_point"])
        assertEquals("started", analytics.events.first().parameters["outcome"])
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals(
            listOf("navigation" to "started", "terminal" to "succeeded"),
            analytics.breadcrumbs.map { it.stage to it.outcome },
        )
        assertTrue(analytics.breadcrumbs.all { it.operation == "navigation_back" })
        assertTrue(analytics.breadcrumbs.all { it.correlationId == context.correlationId })
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun unappliedLoginBackReportsOneFailureAtTheRootBoundary() {
        val analytics = RecordingAnalytics()
        val context = RootLoginBackContext(
            sourceScreen = "login",
            destinationScreen = "server_management",
            entryPoint = "toolbar_back",
            correlationId = "qa-correlation",
        )

        logRootLoginBackAttempt(analytics, context)
        logRootLoginBackCompleted(analytics, context, applied = false)

        assertEquals("failed", analytics.events.last().parameters["outcome"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals("back_navigation_not_applied", analytics.exceptions.single().second.reasonCode)
        assertEquals("terminal", analytics.breadcrumbs.last().stage)
        assertEquals("failed", analytics.breadcrumbs.last().outcome)
    }

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val breadcrumbs = mutableListOf<DiagnosticContext>()
        val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext>>()

        override fun logException(throwable: Throwable, message: String?) = Unit
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
}
