package com.retro99.parrot.lifecycle

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppVisibilityReporterTest {

    @Test
    fun reportsOneBackgroundForegroundPairAndIgnoresRotationAndFinish() {
        val analytics = RecordingAnalytics()
        val reporter = AppVisibilityReporter(analytics)

        // Initial launch and configuration recreation do not count as an app resume.
        reporter.onActivityStarted()
        reporter.onActivityStopped(isChangingConfigurations = true, isFinishing = false)
        reporter.onActivityStarted()
        assertTrue(analytics.events.isEmpty())

        // Duplicate stop/start callbacks are bounded to one transition per state change.
        reporter.onActivityStopped(isChangingConfigurations = false, isFinishing = false)
        reporter.onActivityStopped(isChangingConfigurations = false, isFinishing = false)
        reporter.onActivityStarted()
        reporter.onActivityStarted()
        reporter.onActivityStopped(isChangingConfigurations = false, isFinishing = true)

        assertEquals(
            listOf("background", "foreground"),
            analytics.events.map { it.parameters["stage"] },
        )
        assertEquals(
            listOf("backgrounded", "foregrounded"),
            analytics.events.map { it.parameters["outcome"] },
        )
        assertEquals(
            listOf("background", "foreground"),
            analytics.breadcrumbs.map { it.stage },
        )
        assertTrue(analytics.breadcrumbs.all { it.screen == "app" && it.operation == "app_visibility" })
    }

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val breadcrumbs = mutableListOf<DiagnosticContext>()

        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun logException(throwable: Throwable, context: DiagnosticContext) = Unit
        override fun logBreadcrumb(context: DiagnosticContext) {
            breadcrumbs += context
        }
        override fun logEvent(event: AnalyticsEvent) {
            events += event
        }
        override fun setUserId(userId: String?) = Unit
    }
}
