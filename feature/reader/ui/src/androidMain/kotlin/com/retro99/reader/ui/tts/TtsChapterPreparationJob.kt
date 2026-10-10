package com.retro99.reader.ui.tts

import android.content.Context
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ReaderAnalyticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * The one chapter preparation, app-wide: it must outlive the reader screen, so it is a
 * `@Single` with its own scope rather than anything reader-scoped. The foreground service
 * only keeps the process alive and shows the progress; the work runs here.
 */
@Single
class TtsChapterPreparationJob(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
    private val generator: TtsAudioGenerator,
    private val prepared: TtsPreparedAudioStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Where a finished chapter is offered for backup. Set by
     * `TtsPreparedChapterBackup`, which is created at app start; nothing else
     * reads it, and preparation works exactly as before when it is null.
     */
    internal var onPrepared: ((PreparedChapterId, PreparedVoiceSettings) -> Unit)? = null

    private val core = TtsChapterPreparationCore(
        sentences = GeneratorSentences(generator),
        chapters = StoreChapters(prepared) { id, settings -> onPrepared?.invoke(id, settings) },
        scope = scope,
        analytics = AnalyticsLog(analytics),
        usableBytes = { context.filesDir.usableSpace },
    )

    val state: StateFlow<TtsChapterPreparationState> = core.state

    internal fun start(input: TtsChapterPreparationInput): TtsChapterPreparationRequest =
        core.start(input)

    fun cancel() = core.cancel()

    internal fun key(text: String, settings: PreparedVoiceSettings): String =
        core.key(text, settings)
}

private class GeneratorSentences(private val generator: TtsAudioGenerator) : TtsPreparationSentenceSource {
    override fun key(text: String, settings: PreparedVoiceSettings): String =
        generator.preparedKey(text, settings.voiceId, settings.rate, settings.pitch)

    override suspend fun prepare(
        id: PreparedChapterId,
        text: String,
        settings: PreparedVoiceSettings,
    ): PreparedAudioEncoding =
        generator.prepareSentence(id, text, settings.voiceId, settings.rate, settings.pitch)
}

private class StoreChapters(
    private val prepared: TtsPreparedAudioStore,
    private val onPrepared: (PreparedChapterId, PreparedVoiceSettings) -> Unit,
) : TtsPreparationChapterStore {
    override fun begin(id: PreparedChapterId, settings: PreparedVoiceSettings, keys: List<String>) {
        prepared.store.begin(id, settings, keys)
    }

    override fun isPrepared(id: PreparedChapterId, key: String): Boolean =
        prepared.store.lookup(id, key) != null

    override fun markComplete(id: PreparedChapterId) = prepared.store.markComplete(id)

    override fun enforceLimit(active: PreparedChapterId) = prepared.store.enforceLimit(active)

    override fun prepared(id: PreparedChapterId, settings: PreparedVoiceSettings) =
        onPrepared(id, settings)
}

/** Two events per preparation, with no text, no titles and no identifiers. */
private class AnalyticsLog(private val analytics: Analytics) : TtsChapterPreparationAnalytics {
    override fun log(event: TtsChapterPreparationAnalyticsEvent) {
        when (event) {
            is TtsChapterPreparationAnalyticsEvent.Started -> analytics.logEvent(
                ReaderAnalyticsEvent.TtsChapterPreparationStarted(
                    voiceKind = event.voiceKind.analyticsValue,
                    sentenceCount = event.sentenceCount.toLong(),
                ),
            )

            is TtsChapterPreparationAnalyticsEvent.Ended -> analytics.logEvent(
                ReaderAnalyticsEvent.TtsChapterPreparationEnded(
                    voiceKind = event.voiceKind.analyticsValue,
                    sentenceCount = event.sentenceCount.toLong(),
                    outcome = event.outcome,
                    durationMs = event.durationMs,
                ),
            )
        }
    }
}
