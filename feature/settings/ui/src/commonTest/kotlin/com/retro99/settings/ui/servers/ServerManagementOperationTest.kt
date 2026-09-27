package com.retro99.settings.ui.servers

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import com.retro99.server.api.ServerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerManagementOperationTest {
    @Test
    fun successLogsAttemptBeforeMutationAndCompletionAfterMutation() = runTest {
        for (operation in ServerManagementAnalyticsEvent.Operation.entries) {
            val analytics = RecordingAnalytics()
            val succeeded = runServerManagementOperation(
                analytics = analytics,
                operation = operation,
                serverType = ServerType.Storyteller,
                isRetry = false,
            ) {
                analytics.order += "mutation"
            }

            assertTrue(succeeded)
            assertEquals(
                listOf(
                    "event:${operation.attemptedEventName}",
                    "breadcrumb:started:started",
                    "mutation",
                    "event:${if (operation == ServerManagementAnalyticsEvent.Operation.Logout) "server_logged_out" else "server_removed"}",
                    "breadcrumb:terminal:succeeded",
                ),
                analytics.order,
            )
            assertEquals("storyteller", analytics.events.first().parameters["server_type"])
            assertEquals(false, analytics.events.first().parameters["is_retry"])
            assertTrue(analytics.exceptions.isEmpty())
            assertEquals(2, analytics.breadcrumbs.size)
            assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        }
    }

    @Test
    fun failureEmitsOneFailedOutcomeAndOneContextualException() = runTest {
        val analytics = RecordingAnalytics()
        val succeeded = runServerManagementOperation(
            analytics = analytics,
            operation = ServerManagementAnalyticsEvent.Operation.Logout,
            serverType = ServerType.Storyteller,
            isRetry = true,
        ) {
            analytics.order += "mutation"
            throw IllegalStateException("private server response and URL")
        }

        assertFalse(succeeded)
        assertEquals("server_logout_attempted", analytics.events[0].name)
        assertEquals("server_logout_failed", analytics.events[1].name)
        assertEquals("server_logout_failed", analytics.events[1].parameters["reason_code"])
        assertEquals(true, analytics.events[0].parameters["is_retry"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals("failed", analytics.exceptions.single().second.outcome)
        assertEquals(2, analytics.breadcrumbs.size)
        assertEquals("failed", analytics.breadcrumbs.last().outcome)
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
    }

    @Test
    fun cancellationIsRecordedButRethrownWithoutExceptionReport() = runTest {
        val analytics = RecordingAnalytics()
        var cancellationThrown = false

        try {
            runServerManagementOperation(
                analytics = analytics,
                operation = ServerManagementAnalyticsEvent.Operation.Remove,
                serverType = ServerType.Storyteller,
                isRetry = false,
            ) {
                throw CancellationException("expected cancellation")
            }
        } catch (_: CancellationException) {
            cancellationThrown = true
        }

        assertTrue(cancellationThrown)
        assertEquals("server_remove_attempted", analytics.events[0].name)
        assertEquals("server_remove_cancelled", analytics.events[1].name)
        assertEquals("cancelled", analytics.events[1].parameters["outcome"])
        assertTrue(analytics.exceptions.isEmpty())
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
    }

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val breadcrumbs = mutableListOf<DiagnosticContext>()
        val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext>>()
        val order = mutableListOf<String>()

        override fun logException(throwable: Throwable, message: String?) = Unit

        override fun logException(throwable: Throwable, context: DiagnosticContext) {
            exceptions += throwable to context
            order += "exception:${context.reasonCode}"
        }

        override fun logBreadcrumb(context: DiagnosticContext) {
            breadcrumbs += context
            order += "breadcrumb:${context.stage}:${context.outcome}"
        }

        override fun logEvent(event: AnalyticsEvent) {
            events += event
            order += "event:${event.name}"
        }

        override fun setUserId(userId: String?) = Unit
    }
}
