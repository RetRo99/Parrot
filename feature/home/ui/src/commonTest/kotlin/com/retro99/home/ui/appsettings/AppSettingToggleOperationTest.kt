package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppSettingToggleOperationTest {
    private val setting = AppSettingsAnalyticsEvent.SettingToggle.ShowContinueReading

    @Test
    fun successfulToggleEmitsOneAttemptSuccessAndMatchingBreadcrumbs() {
        val analytics = RecordingToggleAnalytics()
        var persisted = false

        val succeeded = executeAppSettingToggle(
            analytics = analytics,
            setting = setting,
            isEnabled = false,
            isRetry = false,
            persist = { persisted = true },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.ShowContinueReadingToggled(isEnabled = false, isRetry = isRetry)
            },
        )

        assertTrue(succeeded)
        assertTrue(persisted)
        assertEquals(
            listOf("app_setting_toggle_attempted", "show_continue_reading_toggled"),
            analytics.events.map { it.name },
        )
        assertEquals(listOf("started" to "started", "terminal" to "succeeded"), analytics.breadcrumbs.map { it.stage to it.outcome })
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals("show_continue_reading", analytics.breadcrumbs.first().operation)
        assertEquals(false, analytics.events.last().parameters["is_retry"])
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun failedWriteHasNoSuccessAndSuccessfulRetryIsClassified() {
        val analytics = RecordingToggleAnalytics()
        val failure = IllegalStateException("private storage path")

        val firstSucceeded = executeAppSettingToggle(
            analytics = analytics,
            setting = setting,
            isEnabled = false,
            isRetry = false,
            persist = { throw failure },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.ShowContinueReadingToggled(isEnabled = false, isRetry = isRetry)
            },
        )
        val retrySucceeded = executeAppSettingToggle(
            analytics = analytics,
            setting = setting,
            isEnabled = false,
            isRetry = true,
            persist = {},
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.ShowContinueReadingToggled(isEnabled = false, isRetry = isRetry)
            },
        )

        assertFalse(firstSucceeded)
        assertTrue(retrySucceeded)
        assertEquals(
            listOf(
                "app_setting_toggle_attempted",
                "app_setting_toggle_failed",
                "app_setting_toggle_attempted",
                "show_continue_reading_toggled",
            ),
            analytics.events.map { it.name },
        )
        assertEquals(false, analytics.events[1].parameters["is_retry"])
        assertEquals(true, analytics.events[2].parameters["is_retry"])
        assertEquals(true, analytics.events.last().parameters["is_retry"])
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        val failureContext = analytics.exceptions.single().second
        assertEquals("app_settings", failureContext?.screen)
        assertEquals("toggle_setting", failureContext?.action)
        assertEquals("show_continue_reading", failureContext?.operation)
        assertEquals("failed", failureContext?.outcome)
        assertEquals("preference_write_failed", failureContext?.reasonCode)
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals(analytics.breadcrumbs[2].correlationId, analytics.breadcrumbs[3].correlationId)
    }

    @Test
    fun cancellationIsNotReportedAsUnexpectedFailure() {
        val analytics = RecordingToggleAnalytics()
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            executeAppSettingToggle(
                analytics = analytics,
                setting = setting,
                isEnabled = true,
                isRetry = false,
                persist = { throw cancellation },
                successEvent = { isRetry ->
                    AppSettingsAnalyticsEvent.ShowContinueReadingToggled(isEnabled = true, isRetry = isRetry)
                },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("app_setting_toggle_attempted", "app_setting_toggle_cancelled"), analytics.events.map { it.name })
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
    }
}

private class RecordingToggleAnalytics : Analytics {
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
