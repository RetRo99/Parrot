package com.retro99.cloudaccount.ui

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.CloudAccountAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CloudAccountModeTelemetryTest {
    @Test
    fun modeChangeEmitsExistingUsageEventAndCorrelatedStartAndTerminalBreadcrumbs() {
        val analytics = ModeRecordingAnalytics()

        CloudAccountModeTelemetry(analytics).onModeChanged("create_account")

        val event = analytics.events.single()
        assertEquals("cloud_account_mode_changed", event.name)
        assertEquals("create_account", event.parameters["mode"])
        assertFalse("correlation_id" in event.parameters)

        assertEquals(2, analytics.breadcrumbs.size)
        val (started, terminal) = analytics.breadcrumbs
        assertEquals(listOf("started", "terminal"), analytics.breadcrumbs.map { it.stage })
        assertEquals(listOf("started", "succeeded"), analytics.breadcrumbs.map { it.outcome })
        analytics.breadcrumbs.forEach { breadcrumb ->
            assertEquals("sync_and_backup", breadcrumb.screen)
            assertEquals("form_mode_switch", breadcrumb.entryPoint)
            assertEquals("change_account_form_mode", breadcrumb.action)
            assertEquals("cloud_account_mode_change", breadcrumb.operation)
        }
        val correlationId = assertNotNull(started.correlationId)
        assertEquals(correlationId, terminal.correlationId)
        assertTrue(correlationId.matches(Regex("[A-Fa-f0-9-]{16,64}")))
        assertTrue(analytics.exceptions.isEmpty())
    }
}

private class ModeRecordingAnalytics : Analytics {
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
