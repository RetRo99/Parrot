package com.retro99.analytics.implementation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.ProductUsage
import com.retro99.analytics.api.UsageMode
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

@Single(binds = [ProductUsage::class])
class ProductUsageManager(
    @Provided private val analytics: Analytics,
    @Provided private val preferences: Preferences,
) : ProductUsage {
    override var isAppForeground: Boolean = true
        private set

    override fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
    }

    override fun appLaunched() {
        runCatching {
            if (preferences.getLong(PreferencesKey.AnalyticsFirstLaunch) == 0L) {
                preferences.putLong(PreferencesKey.AnalyticsFirstLaunch, Clock.System.now().toEpochMilliseconds())
            }
        }
    }

    override fun meaningfulSession(mode: UsageMode) {
        runCatching {
            if (preferences.getBoolean(PreferencesKey.AnalyticsActivated)) return@runCatching
            appLaunched()
            val sinceLaunch = (Clock.System.now().toEpochMilliseconds() -
                preferences.getLong(PreferencesKey.AnalyticsFirstLaunch)).coerceAtLeast(0L)
            preferences.putBoolean(PreferencesKey.AnalyticsActivated, true)
            analytics.logEvent(ProductAnalyticsEvent.FirstMeaningfulSession(mode, sinceLaunch))
        }
    }

    override fun observeCompletion(localBookKey: String, progression: Double, mode: UsageMode, initial: Boolean) {
        if (!progression.isFinite() || progression !in 0.0..1.0) return
        // Telemetry persistence must never stop a reader or playback callback.
        runCatching {
            val profile = preferences.getStringOrNull(PreferencesKey.ActiveProfileId) ?: "default"
            val key = PreferencesKey.AnalyticsBookCompleted(profile, localBookKey)
            val completed = progression >= 0.98
            val wasCompleted = preferences.getBoolean(key)
            if (completed != wasCompleted) preferences.putBoolean(key, completed)
            // Opening an already-finished book is not a new completion.
            if (completed && !wasCompleted && !initial) {
                analytics.logEvent(ProductAnalyticsEvent.BookCompleted(mode))
            }
        }
    }
}
