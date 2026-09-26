package com.retro99.analytics.api

interface Analytics {
    /**
     * Logs an exception to crashlytics with an optional context message.
     * Also writes to the local log file for user sharing.
     */
    fun logException(throwable: Throwable, message: String?)

    /** Adds a bounded, non-fatal operation breadcrumb to the configured diagnostic provider. */
    fun logBreadcrumb(context: DiagnosticContext) {
        // Preserve source compatibility. Production and debug providers override this method.
    }

    /** Reports an unexpected failure with structured, bounded context in diagnostic breadcrumbs. */
    fun logException(throwable: Throwable, context: DiagnosticContext) {
        // Preserve source compatibility for alternate providers. Production/debug providers
        // override this overload so the typed context is validated and retained.
        logException(throwable, null)
    }

    /**
     * Logs an analytics event for tracking user behavior and app usage.
     *
     * @param event The analytics event to log
     */
    fun logEvent(event: AnalyticsEvent)

    /**
     * Sets a policy-approved, non-identifying user ID for analytics tracking, or null to clear it.
     * Do not treat an unkeyed hash of a username, email, URL, or other identifier as anonymous.
     */
    fun setUserId(userId: String?)
}

/** Clears any previously persisted provider identity when no approved anonymous ID is available. */
fun Analytics.clearUserIdentity() {
    setUserId(null)
}
