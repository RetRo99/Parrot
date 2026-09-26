package com.retro99.analytics.implementation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.FileLogger
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import dev.gitlive.firebase.analytics.FirebaseAnalytics
import dev.gitlive.firebase.crashlytics.FirebaseCrashlytics

class AnalyticsManager(
    private val firebaseAnalytics: FirebaseAnalytics,
    private val firebaseCrashlytics: FirebaseCrashlytics,
    private val fileLogger: FileLogger,
    private val preferences: Preferences,
) : Analytics {

    override fun logException(throwable: Throwable, message: String?) {
        // Log to Crashlytics
        message?.let { firebaseCrashlytics.log(it) }
        firebaseCrashlytics.recordException(throwable)

        // Also log to file for user sharing (if enabled and not crash-only)
        if (shouldLogHandledExceptionsToFile()) {
            fileLogger.logException(throwable, message)
        }
    }

    override fun logEvent(event: AnalyticsEvent) {
        val parameters = sanitizeAnalyticsParameters(event.parameters).takeIf { it.isNotEmpty() }
        firebaseAnalytics.logEvent(event.name, parameters)
    }

    override fun setUserId(userId: String?) {
        firebaseAnalytics.setUserId(userId)
        firebaseCrashlytics.setUserId(userId ?: "")
    }

    private fun shouldLogHandledExceptionsToFile(): Boolean =
        preferences.getBoolean(PreferencesKey.FileLoggingEnabled, defaultValue = false) &&
            !preferences.getBoolean(PreferencesKey.FileLoggingCrashesOnly, defaultValue = false)
}
