package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileOperationTest {
    @Test
    fun successfulOperationEmitsOneAttemptSuccessAndCorrelatedBreadcrumbs() = runTest {
        val analytics = ProfileRecordingAnalytics()
        var executed = false

        val succeeded = executeProfileOperation(
            analytics = analytics,
            operation = AppSettingsAnalyticsEvent.ProfileOperation.Create,
            isRetry = false,
            successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileCreated(isRetry) },
        ) {
            executed = true
        }

        assertTrue(succeeded)
        assertTrue(executed)
        assertEquals(
            listOf("profile_operation_attempted", "profile_created"),
            analytics.events.map { it.name },
        )
        assertEquals(
            listOf("started" to "started", "terminal" to "succeeded"),
            analytics.breadcrumbs.map { it.stage to it.outcome },
        )
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
        assertFalse(analytics.events.any { "profile_name" in it.parameters || "profile_id" in it.parameters })
    }

    @Test
    fun unexpectedFailureEmitsOneFailureReportAndKeepsBoundedContext() = runTest {
        val analytics = ProfileRecordingAnalytics()
        val failure = IllegalStateException("private profile storage detail")

        val succeeded = executeProfileOperation(
            analytics = analytics,
            operation = AppSettingsAnalyticsEvent.ProfileOperation.Switch,
            isRetry = false,
            successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileSwitched(isRetry) },
        ) {
            throw failure
        }

        assertFalse(succeeded)
        assertEquals(
            listOf("profile_operation_attempted", "profile_operation_failed"),
            analytics.events.map { it.name },
        )
        assertEquals("failed", analytics.events.last().parameters["outcome"])
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        val context = analytics.exceptions.single().second
        assertEquals("app_settings", context?.screen)
        assertEquals("switch_profile", context?.action)
        assertEquals("profile_switch", context?.operation)
        assertEquals("profile_operation_failed", context?.reasonCode)
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
    }

    @Test
    fun cancellationIsRecordedWithoutExceptionReportAndPropagates() = runTest {
        val analytics = ProfileRecordingAnalytics()
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            executeProfileOperation(
                analytics = analytics,
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Delete,
                isRetry = true,
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileDeleted(isRetry) },
            ) {
                throw cancellation
            }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(
            listOf("profile_operation_attempted", "profile_operation_cancelled"),
            analytics.events.map { it.name },
        )
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun retryAttemptAndTerminalOutcomeRetainRetryAttribution() = runTest {
        val analytics = ProfileRecordingAnalytics()
        var shouldFail = true

        suspend fun submit(): Boolean = executeProfileOperation(
            analytics = analytics,
            operation = AppSettingsAnalyticsEvent.ProfileOperation.Rename,
            isRetry = !shouldFail,
            successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileRenamed(isRetry) },
        ) {
            if (shouldFail) error("private test failure")
        }

        assertFalse(submit())
        shouldFail = false
        assertTrue(submit())

        assertEquals(
            listOf("profile_operation_attempted", "profile_operation_failed", "profile_operation_attempted", "profile_renamed"),
            analytics.events.map { it.name },
        )
        assertEquals(listOf(false, false, true, true), analytics.events.map { it.parameters["is_retry"] })
    }
}

private class ProfileRecordingAnalytics : Analytics {
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
