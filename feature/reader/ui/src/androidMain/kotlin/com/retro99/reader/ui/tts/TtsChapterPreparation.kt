package com.retro99.reader.ui.tts

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One chapter's worth of work, frozen at the moment the button was pressed. */
internal data class TtsChapterPreparationInput(
    val id: PreparedChapterId,
    val settings: PreparedVoiceSettings,
    val voiceKind: TtsPreparationVoiceKind,
    val texts: List<String>,
)

/** The generator, behind a seam a host test can fake. */
internal interface TtsPreparationSentenceSource {
    fun key(text: String, settings: PreparedVoiceSettings): String
    suspend fun prepare(id: PreparedChapterId, text: String, settings: PreparedVoiceSettings): PreparedAudioEncoding

    /** As [prepare], saying how long generating and encoding took when this call did both. */
    suspend fun prepareMeasured(
        id: PreparedChapterId,
        text: String,
        settings: PreparedVoiceSettings,
    ): PreparedSentenceWork = PreparedSentenceWork(prepare(id, text, settings))
}

/**
 * One sentence's outcome. [workMs] is the time synthesis and encoding took, without any
 * wait for the synthesis turn; null when nothing was generated (already prepared, copied
 * from another chapter, or made from a cached WAV), which is no measure of this voice.
 */
internal data class PreparedSentenceWork(val encoding: PreparedAudioEncoding, val workMs: Long? = null)

/** The prepared store, behind a seam a host test can fake. */
internal interface TtsPreparationChapterStore {
    fun begin(id: PreparedChapterId, settings: PreparedVoiceSettings, keys: List<String>)
    fun isPrepared(id: PreparedChapterId, key: String): Boolean
    fun markComplete(id: PreparedChapterId)
    fun enforceLimit(active: PreparedChapterId)

    /**
     * The chapter is finished and settled on disk. Where backup is offered it,
     * and nothing else happens here, so a host test needs no backup at all.
     */
    fun prepared(id: PreparedChapterId, settings: PreparedVoiceSettings) = Unit
}

internal class TtsChapterPreparationCore(
    private val sentences: TtsPreparationSentenceSource,
    private val chapters: TtsPreparationChapterStore,
    private val scope: CoroutineScope,
    private val analytics: TtsChapterPreparationAnalytics,
    private val usableBytes: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    @Suppress("UnusedPrivateProperty")
    private val speed: TtsPreparationSpeedLog = TtsPreparationSpeedLog { _, _ -> },
) {
    private val mutableState = MutableStateFlow<TtsChapterPreparationState>(TtsChapterPreparationState.Idle)
    val state: StateFlow<TtsChapterPreparationState> = mutableState.asStateFlow()

    @Volatile
    private var cancelRequested = false

    /**
     * Claims the job and starts the work, or says why it did not. The claim is made before
     * this returns, so a second press cannot start a second chapter.
     */
    fun start(input: TtsChapterPreparationInput): TtsChapterPreparationRequest {
        if (input.texts.isEmpty()) return TtsChapterPreparationRequest.UNAVAILABLE
        while (true) {
            val current = mutableState.value
            if (current is TtsChapterPreparationState.Running) {
                return TtsChapterPreparationRequest.ALREADY_PREPARING
            }
            if (usableBytes() < requiredBytes(input.texts.size)) {
                mutableState.value = TtsChapterPreparationState.Failed(
                    input.id.chapterHref,
                    TtsChapterPreparationFailure.NOT_ENOUGH_SPACE,
                )
                return TtsChapterPreparationRequest.NOT_ENOUGH_SPACE
            }
            val claimed = mutableState.compareAndSet(
                current,
                TtsChapterPreparationState.Running(input.id.chapterHref, done = 0, total = input.texts.size),
            )
            if (!claimed) continue
            cancelRequested = false
            scope.launch { run(input) }
            return TtsChapterPreparationRequest.STARTED
        }
    }

    fun key(text: String, settings: PreparedVoiceSettings): String = sentences.key(text, settings)

    /** Stops after the sentence in flight: native synthesis already running is never cut off. */
    fun cancel() {
        cancelRequested = true
    }

    private suspend fun run(input: TtsChapterPreparationInput) {
        val startedAt = now()
        analytics.log(TtsChapterPreparationAnalyticsEvent.Started(input.voiceKind, input.texts.size))
        var outcome = OUTCOME_FAILED
        try {
            val keys = input.texts.map { text -> sentences.key(text, input.settings) }
            chapters.begin(input.id, input.settings, keys)
            var done = 0
            for ((index, text) in input.texts.withIndex()) {
                if (cancelRequested) {
                    outcome = OUTCOME_CANCELLED
                    mutableState.value = TtsChapterPreparationState.Cancelled(input.id.chapterHref)
                    return
                }
                if (!chapters.isPrepared(input.id, keys[index])) {
                    // One retry: a slow voice occasionally fails a single sentence.
                    var encoded = sentences.prepare(input.id, text, input.settings)
                    if (encoded is PreparedAudioEncoding.Failure) {
                        encoded = sentences.prepare(input.id, text, input.settings)
                    }
                    if (encoded is PreparedAudioEncoding.Failure) {
                        mutableState.value = TtsChapterPreparationState.Failed(
                            input.id.chapterHref,
                            TtsChapterPreparationFailure.SENTENCE_FAILED,
                        )
                        return
                    }
                }
                done++
                mutableState.value =
                    TtsChapterPreparationState.Running(input.id.chapterHref, done, input.texts.size)
            }
            chapters.markComplete(input.id)
            chapters.enforceLimit(input.id)
            chapters.prepared(input.id, input.settings)
            outcome = OUTCOME_COMPLETED
            mutableState.value = TtsChapterPreparationState.Completed(input.id.chapterHref)
        } catch (cancelled: CancellationException) {
            outcome = OUTCOME_CANCELLED
            mutableState.value = TtsChapterPreparationState.Cancelled(input.id.chapterHref)
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Chapter preparation failed", error)
            mutableState.value = TtsChapterPreparationState.Failed(
                input.id.chapterHref,
                TtsChapterPreparationFailure.UNEXPECTED_ERROR,
            )
        } finally {
            analytics.log(
                TtsChapterPreparationAnalyticsEvent.Ended(
                    voiceKind = input.voiceKind,
                    sentenceCount = input.texts.size,
                    outcome = outcome,
                    durationMs = now() - startedAt,
                ),
            )
        }
    }

    /** Enough room for the chapter's compressed audio and the manifest, with headroom. */
    private fun requiredBytes(sentenceCount: Int): Long =
        FREE_SPACE_HEADROOM_BYTES + sentenceCount * ESTIMATED_SENTENCE_BYTES

    private companion object {
        const val TAG = "TtsChapterPreparation"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELLED = "cancelled"
        const val FREE_SPACE_HEADROOM_BYTES = 32L * 1_024 * 1_024
        /** Measured AAC sentences were 11 to 20 kB; 64 kB per sentence is a safe over-estimate. */
        const val ESTIMATED_SENTENCE_BYTES = 64L * 1_024
    }
}

/** One start event and one end event; nothing per sentence and no text, title or id. */
internal fun interface TtsChapterPreparationAnalytics {
    fun log(event: TtsChapterPreparationAnalyticsEvent)
}

internal sealed interface TtsChapterPreparationAnalyticsEvent {
    data class Started(val voiceKind: TtsPreparationVoiceKind, val sentenceCount: Int) : TtsChapterPreparationAnalyticsEvent
    data class Ended(
        val voiceKind: TtsPreparationVoiceKind,
        val sentenceCount: Int,
        val outcome: String,
        val durationMs: Long,
    ) : TtsChapterPreparationAnalyticsEvent
}
