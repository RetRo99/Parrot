package com.retro99.parrot.lifecycle

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ProductUsage
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Reports real foreground/background transitions once, excluding rotation and Activity finish. */
@Single
class AppVisibilityReporter(
    @Provided private val analytics: Analytics,
    @Provided private val productUsage: ProductUsage? = null,
) {
    private var isInBackground = false

    fun onActivityStarted() {
        productUsage?.setAppForeground(true)
        if (!isInBackground) return
        isInBackground = false
        report(NavigationAnalyticsEvent.AppVisibility.Foreground)
    }

    fun onActivityStopped(isChangingConfigurations: Boolean, isFinishing: Boolean) {
        if (!isChangingConfigurations) productUsage?.setAppForeground(false)
        if (isChangingConfigurations || isFinishing || isInBackground) return
        isInBackground = true
        report(NavigationAnalyticsEvent.AppVisibility.Background)
    }

    private fun report(visibility: NavigationAnalyticsEvent.AppVisibility) {
        analytics.logEvent(NavigationAnalyticsEvent.AppVisibilityChanged(visibility))
        val isForeground = visibility == NavigationAnalyticsEvent.AppVisibility.Foreground
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "app",
                action = "lifecycle",
                operation = "app_visibility",
                stage = visibility.value,
                outcome = if (isForeground) "foregrounded" else "backgrounded",
            ),
        )
    }
}
