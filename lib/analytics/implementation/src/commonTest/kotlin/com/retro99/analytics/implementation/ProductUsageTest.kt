package com.retro99.analytics.implementation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.FeatureExposureTracker
import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.ReaderOpenTracker
import com.retro99.analytics.api.UsageEndReason
import com.retro99.analytics.api.UsageFeature
import com.retro99.analytics.api.UsageMode
import com.retro99.analytics.api.UsageSession
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductUsageTest {
    @Test
    fun providerFailureDoesNotBreakSessionsOrReplayTheirDeltas() {
        var calls = 0
        val analytics = object : Analytics {
            override fun logEvent(event: AnalyticsEvent) { calls++; error("provider unavailable") }
            override fun logException(throwable: Throwable, message: String?) = Unit
            override fun setUserId(userId: String?) = Unit
        }
        var clock = 0L
        val session = UsageSession(analytics, { error("activation unavailable") }) { clock }
        session.update(UsageMode.Reading, true)
        clock = 120_000
        session.finish(UsageEndReason.Closed)
        session.finish(UsageEndReason.Cleared)
        session.checkpoint()
        assertEquals(1, calls)
        val open = ReaderOpenTracker(analytics, "ebook", "book_detail")
        open.complete(ProductOutcome.Succeeded)
        open.complete(ProductOutcome.Cancelled)
        assertEquals(2, calls)
    }

    @Test
    fun checkpointsAreDeltasAndFinishingTwiceDoesNotDuplicate() {
        val analytics = RecordingProductAnalytics()
        var clock = 0L
        val activated = mutableListOf<UsageMode>()
        val session = UsageSession(analytics, activated::add) { clock }
        session.update(UsageMode.Reading, true)
        clock = 60_000
        session.checkpoint()
        session.update(UsageMode.Tts, true)
        clock = 90_000
        session.update(UsageMode.Tts, false)
        clock = 120_000
        session.finish(UsageEndReason.Closed)
        session.finish(UsageEndReason.Cleared)
        clock = 180_000
        session.update(UsageMode.Reading, true)
        session.checkpoint()

        val summaries = analytics.events.filterIsInstance<ProductAnalyticsEvent.ReadingSessionSummary>()
        assertEquals(2, summaries.size)
        assertEquals(60_000L, summaries.sumOf { it.readingMs })
        assertEquals(60_000L, summaries.sumOf { it.ttsMs })
        assertEquals(90_000L, summaries.sumOf { it.foregroundMs })
        assertEquals(30_000L, summaries.sumOf { it.backgroundMs })
        assertEquals(listOf(UsageMode.Tts), activated)
        assertEquals(60_000L, analytics.events.filterIsInstance<ProductAnalyticsEvent.PlaybackSessionSummary>().sumOf { it.playingMs })
    }

    @Test
    fun sourceSwitchRetainsReadingTotalsButSeparatesPlaybackSources() {
        val analytics = RecordingProductAnalytics()
        var clock = 0L
        val session = UsageSession(analytics, {}) { clock }
        session.update(UsageMode.ReadAloud, true)
        clock = 10_000
        session.update(UsageMode.Tts, true)
        clock = 30_000
        session.finish(UsageEndReason.Closed)
        val reading = analytics.events.filterIsInstance<ProductAnalyticsEvent.ReadingSessionSummary>().single()
        assertEquals(10_000L, reading.readAloudMs)
        assertEquals(20_000L, reading.ttsMs)
        val playback = analytics.events.filterIsInstance<ProductAnalyticsEvent.PlaybackSessionSummary>()
        assertEquals(listOf(UsageMode.ReadAloud, UsageMode.Tts), playback.map { it.mode })
        assertEquals(listOf(10_000L, 20_000L), playback.map { it.playingMs })
    }

    @Test
    fun loadingAndInactiveTimeDoNotCountTowardActivation() {
        val analytics = RecordingProductAnalytics()
        var clock = 0L
        var activations = 0
        val session = UsageSession(analytics, { activations++ }) { clock }
        session.update(null, false, buffering = true, listeningMode = UsageMode.Audiobook)
        clock = 180_000
        session.checkpoint()
        assertEquals(0, activations)
        assertTrue(analytics.events.none { it is ProductAnalyticsEvent.ReadingSessionSummary })
        assertEquals(180_000L, analytics.events.filterIsInstance<ProductAnalyticsEvent.PlaybackSessionSummary>().single().bufferingMs)
        session.update(UsageMode.Audiobook, false)
        clock += 120_000
        session.checkpoint()
        session.update(null, false)
        clock += 300_000
        session.finish(UsageEndReason.Closed)
        assertEquals(1, activations)
        assertEquals(120_000L, analytics.events.filterIsInstance<ProductAnalyticsEvent.ReadingSessionSummary>().sumOf { it.audiobookMs })
    }

    @Test
    fun openResultsAreExactlyOncePerAttemptIncludingRetryAndCancellation() {
        val analytics = RecordingProductAnalytics()
        var clock = 0L
        val tracker = ReaderOpenTracker(analytics, "ebook", "book_detail") { clock }
        clock = 500
        tracker.complete(ProductOutcome.Failed, "publication_open_failed")
        tracker.complete(ProductOutcome.Cancelled)
        tracker.retry()
        clock = 900
        tracker.complete(ProductOutcome.Succeeded)
        tracker.complete(ProductOutcome.Cancelled)
        val results = analytics.events.filterIsInstance<ProductAnalyticsEvent.ReaderOpenCompleted>()
        assertEquals(listOf(ProductOutcome.Failed, ProductOutcome.Succeeded), results.map { it.outcome })
        assertEquals(listOf(500L, 400L), results.map { it.durationMs })
        assertEquals(listOf(false, true), results.map { it.isRetry })
    }

    @Test
    fun exposureIsDeduplicatedPerVisitAndAvailability() {
        val analytics = RecordingProductAnalytics()
        val tracker = FeatureExposureTracker(analytics, "reader")
        tracker.expose(UsageFeature.Tts, false)
        tracker.expose(UsageFeature.Tts, false)
        tracker.expose(UsageFeature.Tts, true)
        tracker.reset()
        tracker.expose(UsageFeature.Tts, true)
        assertEquals(3, analytics.events.size)
    }

    @Test
    fun activationPersistsAcrossManagerInstances() {
        val analytics = RecordingProductAnalytics()
        val preferences = ProductTestPreferences()
        val manager = ProductUsageManager(analytics, preferences)
        manager.appLaunched()
        val launch = preferences.getLong(PreferencesKey.AnalyticsFirstLaunch)
        manager.appLaunched()
        assertEquals(launch, preferences.getLong(PreferencesKey.AnalyticsFirstLaunch))
        manager.meaningfulSession(UsageMode.Reading)
        ProductUsageManager(analytics, preferences).meaningfulSession(UsageMode.Tts)
        assertEquals(1, analytics.events.filterIsInstance<ProductAnalyticsEvent.FirstMeaningfulSession>().size)
    }

    @Test
    fun completionsIgnoreAlreadyFinishedOpensAndPersistPerProfile() {
        val analytics = RecordingProductAnalytics()
        val preferences = ProductTestPreferences()
        val manager = ProductUsageManager(analytics, preferences)
        manager.observeCompletion("private-server:private-book", 1.0, UsageMode.Reading, initial = true)
        manager.observeCompletion("private-server:private-book", 1.0, UsageMode.Reading)
        assertEquals(0, analytics.events.size)
        manager.observeCompletion("private-server:private-book", 0.2, UsageMode.Reading)
        manager.observeCompletion("private-server:private-book", 0.98, UsageMode.Reading)
        ProductUsageManager(analytics, preferences).observeCompletion("private-server:private-book", 1.0, UsageMode.Tts)
        assertEquals(1, analytics.events.size)
        manager.observeCompletion("private-server:private-book", Double.NaN, UsageMode.Reading)
        preferences.putString(PreferencesKey.ActiveProfileId, "another-private-profile")
        manager.observeCompletion("private-server:private-book", 1.0, UsageMode.Tts)
        assertEquals(2, analytics.events.size)
        assertTrue(analytics.events.all { it.parameters.values.none { value -> value.toString().contains("private") } })
    }
}

internal class RecordingProductAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()
    override fun logEvent(event: AnalyticsEvent) { events += event }
    override fun logException(throwable: Throwable, message: String?) = Unit
    override fun setUserId(userId: String?) = Unit
}

private class ProductTestPreferences : Preferences {
    private val values = mutableMapOf<String, Any>()
    override fun getStringOrNull(key: PreferencesKey) = values[key.name] as? String
    override fun putString(key: PreferencesKey, value: String) { values[key.name] = value }
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean) = values[key.name] as? Boolean ?: defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) { values[key.name] = value }
    override fun getLong(key: PreferencesKey, defaultValue: Long) = values[key.name] as? Long ?: defaultValue
    override fun putLong(key: PreferencesKey, value: Long) { values[key.name] = value }
    override fun remove(key: PreferencesKey) { values.remove(key.name) }
    override fun observeStringOrNull(key: PreferencesKey): Nothing = error("Not used")
    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Nothing = error("Not used")
}
