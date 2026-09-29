package com.retro99.parrot.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent

internal data class RootLoginBackContext(
    val sourceScreen: String,
    val destinationScreen: String,
    val entryPoint: String,
    val correlationId: String,
)

internal fun logRootLoginBackAttempt(
    analytics: Analytics,
    context: RootLoginBackContext,
) {
    analytics.logEvent(
        NavigationAnalyticsEvent.BackNavigationAttempted(
            sourceScreen = context.sourceScreen,
            destinationScreen = context.destinationScreen,
            entryPoint = context.entryPoint,
        ),
    )
    analytics.logBreadcrumb(context.toDiagnostic(stage = "navigation", outcome = "started"))
}

internal fun logRootLoginBackCompleted(
    analytics: Analytics,
    context: RootLoginBackContext,
    applied: Boolean,
) {
    val outcome = if (applied) {
        NavigationAnalyticsEvent.BackNavigationOutcome.Succeeded
    } else {
        NavigationAnalyticsEvent.BackNavigationOutcome.Failed
    }
    analytics.logEvent(
        NavigationAnalyticsEvent.BackNavigationCompleted(
            sourceScreen = context.sourceScreen,
            destinationScreen = context.destinationScreen,
            entryPoint = context.entryPoint,
            outcome = outcome,
        ),
    )
    val diagnosticContext = context.toDiagnostic(
        stage = "terminal",
        outcome = outcome.value,
        reasonCode = if (applied) null else "back_navigation_not_applied",
    )
    analytics.logBreadcrumb(diagnosticContext)
    if (!applied) {
        analytics.logException(
            IllegalStateException("Root Login Back navigation did not apply the requested destination."),
            diagnosticContext,
        )
    }
}

private fun RootLoginBackContext.toDiagnostic(
    stage: String,
    outcome: String,
    reasonCode: String? = null,
) = DiagnosticContext(
    screen = sourceScreen,
    sourceScreen = sourceScreen,
    destinationScreen = destinationScreen,
    entryPoint = entryPoint,
    action = "back",
    operation = "navigation_back",
    stage = stage,
    outcome = outcome,
    reasonCode = reasonCode,
    correlationId = correlationId,
)
