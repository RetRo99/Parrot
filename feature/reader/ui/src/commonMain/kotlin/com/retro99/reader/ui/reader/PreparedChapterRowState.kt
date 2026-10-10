package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsChapterPreparationFailure
import com.retro99.reader.ui.tts.TtsChapterPreparationRequest
import com.retro99.reader.ui.tts.TtsChapterPreparationState
import com.retro99.reader.ui.tts.TtsPreparedChapterAudio
import com.retro99.reader.ui.tts.TtsVoice

/** What the "Prepare this chapter" row shows. Null means the row is not shown at all. */
internal sealed interface PreparedChapterRowState {

    data object NotPrepared : PreparedChapterRowState

    data class Preparing(val done: Int, val total: Int) : PreparedChapterRowState

    data object PreparingAnotherChapter : PreparedChapterRowState

    data class Ready(val bytes: Long) : PreparedChapterRowState

    /** Made for a voice or speed that is no longer selected. */
    data class OtherSettings(val voiceId: String?, val rate: Float) : PreparedChapterRowState

    data class Partly(val done: Int, val total: Int) : PreparedChapterRowState

    data class Failed(val reason: TtsChapterPreparationFailure) : PreparedChapterRowState

    data class VoiceUnusable(val needsTerms: Boolean) : PreparedChapterRowState
}

/** Whether the selected voice can prepare anything at all. */
internal enum class PreparedChapterVoice { USABLE, PACK_MISSING, TERMS_REQUIRED }

/** The same gates read-aloud applies before it plays: pack downloaded, terms accepted. */
internal fun preparedChapterVoice(voice: TtsVoice?, hasAcceptedTerms: Boolean): PreparedChapterVoice = when {
    voice == null || !voice.isNeural -> PreparedChapterVoice.USABLE
    voice.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC && !hasAcceptedTerms ->
        PreparedChapterVoice.TERMS_REQUIRED
    voice.needsDownload -> PreparedChapterVoice.PACK_MISSING
    else -> PreparedChapterVoice.USABLE
}

/**
 * The one decision about the row, kept pure so every state is tested rather than discovered
 * on a phone. Not shown for recorded narration or when read-aloud is not available.
 */
@Suppress("LongParameterList", "ReturnCount")
internal fun derivePreparedChapterRow(
    isReadAloudAvailable: Boolean,
    isNarrationSelected: Boolean,
    chapterHref: String?,
    audio: TtsPreparedChapterAudio,
    preparation: TtsChapterPreparationState,
    voice: PreparedChapterVoice,
): PreparedChapterRowState? {
    if (!isReadAloudAvailable || isNarrationSelected || chapterHref == null) return null
    if (preparation is TtsChapterPreparationState.Running) {
        return if (preparation.chapterHref == chapterHref) {
            PreparedChapterRowState.Preparing(preparation.done, preparation.total)
        } else {
            PreparedChapterRowState.PreparingAnotherChapter
        }
    }
    if (voice != PreparedChapterVoice.USABLE) {
        return PreparedChapterRowState.VoiceUnusable(voice == PreparedChapterVoice.TERMS_REQUIRED)
    }
    if (preparation is TtsChapterPreparationState.Failed && preparation.chapterHref == chapterHref) {
        return PreparedChapterRowState.Failed(preparation.reason)
    }
    return when (audio) {
        TtsPreparedChapterAudio.NotPrepared -> PreparedChapterRowState.NotPrepared
        is TtsPreparedChapterAudio.Ready -> PreparedChapterRowState.Ready(audio.bytes)
        is TtsPreparedChapterAudio.Partial ->
            if (audio.done > 0) {
                PreparedChapterRowState.Partly(audio.done, audio.total)
            } else {
                PreparedChapterRowState.NotPrepared
            }
        is TtsPreparedChapterAudio.OtherSettings ->
            PreparedChapterRowState.OtherSettings(audio.voiceId, audio.rate)
    }
}

/** What the screen does with the answer to one press, beyond what the row already shows. */
internal enum class PreparedChapterPressOutcome { NONE, OPEN_VOICES, SHOW_FAILURE }

/**
 * An unusable voice sends the user to the Voices sheet, as a sentence tap does. A denied
 * notification permission gets read-aloud's own failure feedback. Everything else is already
 * visible in the row, including the refusal while another chapter is preparing.
 */
internal fun preparedChapterPressOutcome(
    request: TtsChapterPreparationRequest,
): PreparedChapterPressOutcome = when (request) {
    TtsChapterPreparationRequest.VOICE_UNUSABLE -> PreparedChapterPressOutcome.OPEN_VOICES
    TtsChapterPreparationRequest.NOTIFICATIONS_DENIED,
    TtsChapterPreparationRequest.UNAVAILABLE,
    -> PreparedChapterPressOutcome.SHOW_FAILURE
    TtsChapterPreparationRequest.STARTED,
    TtsChapterPreparationRequest.ALREADY_PREPARING,
    TtsChapterPreparationRequest.NOT_ENOUGH_SPACE,
    -> PreparedChapterPressOutcome.NONE
}
