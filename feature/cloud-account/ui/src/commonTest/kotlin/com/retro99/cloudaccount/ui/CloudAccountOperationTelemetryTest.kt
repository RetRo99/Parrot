package com.retro99.cloudaccount.ui

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.CloudAccountAnalyticsEvent
import com.retro99.analytics.api.CloudAccountOperation
import com.retro99.analytics.api.CloudAccountObservation
import com.retro99.analytics.api.DiagnosticContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudAccountOperationTelemetryTest {
    @Test
    fun successEmitsOneAttemptAndOneCorrelatedTerminalWithoutAnalyticsCorrelationId() = runTest {
        val analytics = RecordingAnalytics()
        val execution = CloudAccountOperationTelemetry(analytics).execute(
            operation = CloudAccountOperation.Authentication,
            entryPoint = "email_submit",
            isRetry = false,
            authMethod = "email",
            mode = "sign_in",
        ) { "account_ready" }

        assertEquals(CloudAccountOperationExecution.Returned("account_ready", CloudAccountOperationOutcome.Succeeded()), execution)
        assertEquals(
            listOf("cloud_account_operation_attempted", "cloud_account_operation_succeeded"),
            analytics.events.map { it.name },
        )
        assertEquals(listOf("started" to "started", "terminal" to "succeeded"), analytics.breadcrumbs.map { it.stage to it.outcome })
        assertEquals(1, analytics.breadcrumbs.mapNotNull { it.correlationId }.distinct().size)
        assertTrue(analytics.events.all { "correlation_id" !in it.parameters })
        assertEquals(0, analytics.exceptions.size)
    }

    @Test
    fun thrownFailureReportsOnceOnlyWhenTheOperationPolicyMarksItUnexpected() = runTest {
        val analytics = RecordingAnalytics()
        val execution = CloudAccountOperationTelemetry(analytics).execute(
            operation = CloudAccountOperation.AutoBackup,
            entryPoint = "confirm_button",
            isRetry = false,
            isEnabled = true,
            reportUnexpectedFailure = { true },
            failureReasonCode = { "local_state_persistence_failed" },
        ) {
            error("private database payload")
        }

        assertIs<CloudAccountOperationExecution.Threw>(execution)
        assertEquals(listOf("cloud_account_operation_attempted", "cloud_account_operation_failed"), analytics.events.map { it.name })
        assertEquals(1, analytics.exceptions.size)
        assertEquals("local_state_persistence_failed", analytics.exceptions.single().reasonCode)
        assertEquals("failed", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.events.none { "error_message" in it.parameters || "exception" in it.parameters })
    }

    @Test
    fun expectedFailureDoesNotCreateNonFatalAndRetryIsDistinct() = runTest {
        val analytics = RecordingAnalytics()
        val telemetry = CloudAccountOperationTelemetry(analytics)
        val retryTracker = CloudAccountRetryTracker()
        val operation = CloudAccountOperation.Authentication
        assertEquals(false, retryTracker.isRetry(operation))

        val first = telemetry.execute(
            operation = operation,
            entryPoint = "email_submit",
            isRetry = retryTracker.isRetry(operation),
            authMethod = "email",
            mode = "sign_in",
        ) { error("credential rejection is an expected recovery path") }
        assertIs<CloudAccountOperationExecution.Threw>(first)
        retryTracker.recordFailure(operation)

        val second = telemetry.execute(
            operation = operation,
            entryPoint = "email_submit",
            isRetry = retryTracker.isRetry(operation),
            authMethod = "email",
            mode = "sign_in",
        ) { "signed_in" }
        assertIs<CloudAccountOperationExecution.Returned<String>>(second)
        assertEquals(true, analytics.events[2].parameters["is_retry"])
        assertEquals(0, analytics.exceptions.size)
        retryTracker.recordSuccess(operation)
        assertEquals(false, retryTracker.isRetry(operation))
    }

    @Test
    fun cancellationHasTerminalOutcomeAndIsRethrownWithoutNonFatal() = runTest {
        val analytics = RecordingAnalytics()

        assertFailsWith<CancellationException> {
            CloudAccountOperationTelemetry(analytics).execute(
                operation = CloudAccountOperation.Authentication,
                entryPoint = "google_button",
                isRetry = false,
                authMethod = "google",
            ) {
                throw CancellationException("expected OAuth cancellation")
            }
        }

        assertEquals(
            listOf("cloud_account_operation_attempted", "cloud_account_operation_cancelled"),
            analytics.events.map { it.name },
        )
        assertEquals("operation_cancelled", analytics.events.last().parameters["reason_code"])
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertEquals(0, analytics.exceptions.size)
    }

    @Test
    fun confirmationAndFieldEventsContainOnlyBoundedValues() {
        val mode = CloudAccountAnalyticsEvent.ModeChanged("create_account")
        val visibility = CloudAccountAnalyticsEvent.PasswordVisibilityChanged(isVisible = true)
        val dismissed = CloudAccountAnalyticsEvent.ConfirmationDismissed(
            operation = "delete_account",
            entryPoint = "system_back",
        )

        assertEquals("cloud_account_mode_changed", mode.name)
        assertEquals("create_account", mode.parameters["mode"])
        assertEquals(true, visibility.parameters["is_visible"])
        assertEquals("cancelled", dismissed.parameters["outcome"])
        assertNull(dismissed.parameters["account_id"])
    }

    @Test
    fun observationFailureHasOneCorrelatedNonFatalAndNoSensitiveDimensions() {
        val analytics = RecordingAnalytics()

        reportCloudAccountObservationFailure(
            analytics = analytics,
            observation = CloudAccountObservation.AuthState,
            error = IllegalStateException("private session payload"),
        )

        assertEquals("cloud_account_observation_failed", analytics.events.single().name)
        assertEquals("auth_state", analytics.events.single().parameters["observation"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals(2, analytics.breadcrumbs.size)
        assertEquals(1, analytics.breadcrumbs.map { it.correlationId }.distinct().size)
        assertTrue(analytics.events.none { "email" in it.parameters || "session" in it.parameters })
    }
}

private class RecordingAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()
    val breadcrumbs = mutableListOf<DiagnosticContext>()
    val exceptions = mutableListOf<DiagnosticContext>()

    override fun logException(throwable: Throwable, message: String?) = Unit

    override fun logException(throwable: Throwable, context: DiagnosticContext) {
        exceptions += context
    }

    override fun logBreadcrumb(context: DiagnosticContext) {
        breadcrumbs += context
    }

    override fun logEvent(event: AnalyticsEvent) {
        events += event
    }

    override fun setUserId(userId: String?) = Unit
}
