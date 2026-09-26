package com.retro99.analytics.implementation

import co.touchlab.kermit.Logger
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.FileLogger
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey

class DebugAnalyticsManager(
    private val fileLogger: FileLogger,
    private val preferences: Preferences,
) : Analytics {

    private val logger = Logger.withTag("čič")

    override fun logException(throwable: Throwable, message: String?) {
        reportException(throwable, sanitizeDiagnosticMessage(message))
    }

    override fun logException(throwable: Throwable, context: DiagnosticContext) {
        reportException(
            throwable,
            sanitizeDiagnosticContext(context) ?: "Handled failure; no valid structured context",
        )
    }

    private fun reportException(throwable: Throwable, sanitizedMessage: String?) {
        val sanitizedThrowable = sanitizeDiagnosticThrowable(throwable)
        logger.e(sanitizedThrowable) {
            if (sanitizedMessage.isNullOrEmpty()) {
                "Exception occurred"
            } else {
                "Exception occurred with message: $sanitizedMessage"
            }
        }
        // Also log to file for user sharing (if enabled and not crash-only)
        if (shouldLogHandledExceptionsToFile()) {
            fileLogger.logException(sanitizedThrowable, sanitizedMessage)
        }
    }

    override fun logEvent(event: AnalyticsEvent) {
        val parameters = sanitizeAnalyticsParameters(event.parameters)
        val eventMessage = buildString {
            append("Analytics Event: ${event.name}")
            if (parameters.isNotEmpty()) {
                append(" | Parameters: $parameters")
            }
        }
        logger.d { eventMessage }
    }

    override fun setUserId(userId: String?) {
        logger.d { "Set User ID: ${userId ?: "null (cleared)"}" }
    }

    private fun shouldLogHandledExceptionsToFile(): Boolean =
        preferences.getBoolean(PreferencesKey.FileLoggingEnabled, defaultValue = false) &&
            !preferences.getBoolean(PreferencesKey.FileLoggingCrashesOnly, defaultValue = false)
}
