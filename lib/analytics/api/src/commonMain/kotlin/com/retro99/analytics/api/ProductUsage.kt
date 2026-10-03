package com.retro99.analytics.api

import kotlin.time.TimeSource

/** Device-local activation/completion state. Book keys never leave this interface as telemetry. */
interface ProductUsage {
    val isAppForeground: Boolean
    fun setAppForeground(foreground: Boolean)
    fun appLaunched()
    fun meaningfulSession(mode: UsageMode)
    fun observeCompletion(localBookKey: String, progression: Double, mode: UsageMode, initial: Boolean = false)
}

/** One monotonic reader session. Checkpoints emit deltas, so summing them never double-counts. */
class UsageSession(
    private val analytics: Analytics,
    private val onMeaningfulSession: (UsageMode) -> Unit,
    private val elapsedMillis: () -> Long = monotonicMillis(),
) {
    private var since = elapsedMillis()
    private var mode: UsageMode? = null
    private var foreground = true
    private var buffering = false
    private var finished = false
    private var totalActiveMs = 0L
    private var meaningfulReported = false
    private val durations = mutableMapOf<UsageMode, Long>()
    private var foregroundMs = 0L
    private var backgroundMs = 0L
    private var bufferingMs = 0L
    private var playingMs = 0L
    private var interruptions = 0
    private var playbackMode: UsageMode? = null

    fun update(mode: UsageMode?, foreground: Boolean, buffering: Boolean = false, listeningMode: UsageMode? = null) {
        if (finished) return
        accumulate()
        if (this.mode != null && this.mode != UsageMode.Reading &&
            (mode == null || mode == UsageMode.Reading) && !buffering) interruptions++
        val nextPlaybackMode = listeningMode ?: mode?.takeUnless { it == UsageMode.Reading }
        if (playbackMode != null && nextPlaybackMode != null && playbackMode != nextPlaybackMode) {
            flushPlayback(UsageEndReason.SourceChanged)
        }
        if (nextPlaybackMode != null) playbackMode = nextPlaybackMode
        this.mode = mode
        this.foreground = foreground
        this.buffering = buffering
    }

    fun checkpoint(reason: UsageEndReason = UsageEndReason.Checkpoint) {
        if (finished) return
        accumulate()
        if (foregroundMs + backgroundMs > 0L) {
            analytics.logProductEvent(ProductAnalyticsEvent.ReadingSessionSummary(
                readingMs = durations[UsageMode.Reading] ?: 0L,
                audiobookMs = durations[UsageMode.Audiobook] ?: 0L,
                readAloudMs = durations[UsageMode.ReadAloud] ?: 0L,
                ttsMs = durations[UsageMode.Tts] ?: 0L,
                foregroundMs = foregroundMs,
                backgroundMs = backgroundMs,
                endReason = reason,
            ))
        }
        flushPlayback(reason)
        durations.clear()
        foregroundMs = 0L
        backgroundMs = 0L
    }

    fun finish(reason: UsageEndReason) {
        if (finished) return
        checkpoint(reason)
        finished = true
    }

    private fun accumulate() {
        val now = elapsedMillis()
        val delta = (now - since).coerceAtLeast(0L)
        since = now
        if (buffering) bufferingMs += delta
        mode?.let { activeMode ->
            if (activeMode != UsageMode.Reading) playingMs += delta
            durations[activeMode] = (durations[activeMode] ?: 0L) + delta
            if (foreground) foregroundMs += delta else backgroundMs += delta
            totalActiveMs += delta
            if (!meaningfulReported && totalActiveMs >= 120_000L) {
                meaningfulReported = true
                runCatching { onMeaningfulSession(activeMode) }
            }
        }
    }

    private fun flushPlayback(reason: UsageEndReason) {
        playbackMode?.let { source ->
            if (playingMs > 0L || bufferingMs > 0L || interruptions > 0) {
                analytics.logProductEvent(ProductAnalyticsEvent.PlaybackSessionSummary(source, playingMs, bufferingMs, interruptions, reason))
            }
        }
        playingMs = 0L
        bufferingMs = 0L
        interruptions = 0
    }
}

/** One result per attempt, including retries and close-before-content. */
class ReaderOpenTracker(
    private val analytics: Analytics,
    private val mediaType: String,
    private val entryPoint: String,
    private val elapsedMillis: () -> Long = monotonicMillis(),
) {
    private var startedAt = elapsedMillis()
    private var resolved = false
    private var isRetry = false

    fun retry() {
        complete(ProductOutcome.Cancelled, "superseded_by_retry")
        startedAt = elapsedMillis()
        resolved = false
        isRetry = true
    }

    fun complete(outcome: ProductOutcome, reasonCode: String? = null) {
        if (resolved) return
        resolved = true
        analytics.logProductEvent(ProductAnalyticsEvent.ReaderOpenCompleted(
            mediaType, entryPoint, outcome, (elapsedMillis() - startedAt).coerceAtLeast(0L), isRetry, reasonCode,
        ))
    }
}

/** Deduplicates exposures within one visible screen visit, not across all visits. */
class FeatureExposureTracker(private val analytics: Analytics, private val screen: String) {
    private val reported = mutableSetOf<Pair<UsageFeature, Boolean>>()
    fun expose(feature: UsageFeature, available: Boolean) {
        if (reported.add(feature to available)) analytics.logProductEvent(ProductAnalyticsEvent.FeatureExposed(feature, screen, available))
    }
    fun reset() = reported.clear()
}

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

private fun Analytics.logProductEvent(event: ProductAnalyticsEvent) {
    runCatching { logEvent(event) }
}
