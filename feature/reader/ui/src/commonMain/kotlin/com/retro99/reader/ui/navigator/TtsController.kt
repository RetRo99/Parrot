package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsSentence
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.reader.ui.tts.TtsVoicePreparationState
import kotlinx.coroutines.flow.Flow

sealed interface TtsPlaybackOperation {
    val correlationId: String
    val action: TtsPlaybackAction
    val isRetry: Boolean

    data class Attempted(
        override val correlationId: String,
        override val action: TtsPlaybackAction,
        override val isRetry: Boolean,
    ) : TtsPlaybackOperation

    data class Succeeded(
        override val correlationId: String,
        override val action: TtsPlaybackAction,
        override val isRetry: Boolean,
        val durationMs: Long,
    ) : TtsPlaybackOperation

    data class Failed(
        override val correlationId: String,
        override val action: TtsPlaybackAction,
        override val isRetry: Boolean,
        val durationMs: Long,
        val reasonCode: TtsPlaybackFailureReason,
        val error: Throwable? = null,
    ) : TtsPlaybackOperation

    data class Cancelled(
        override val correlationId: String,
        override val action: TtsPlaybackAction,
        override val isRetry: Boolean,
        val durationMs: Long,
        val reasonCode: TtsPlaybackFailureReason,
    ) : TtsPlaybackOperation
}

enum class TtsPlaybackAction(val analyticsValue: String) {
    CONTROLS("controls"),
    SENTENCE_TAP("sentence_tap"),
    CHAPTER("chapter"),
    RESUME("resume"),
    PREVIEW_RESUME("preview_resume"),
    ACTIVE_PLAYBACK("active_playback"),
}

enum class TtsPlaybackFailureReason(val analyticsValue: String) {
    PERMISSION_DENIED("permission_denied"),
    CONTENT_UNAVAILABLE("content_unavailable"),
    CHAPTER_UNAVAILABLE("chapter_unavailable"),
    SYNTHESIS_FAILED("synthesis_failed"),
    PLAYER_UNAVAILABLE("player_unavailable"),
    PLAYER_ERROR("player_error"),
    START_TIMEOUT("start_timeout"),
    OPERATION_CANCELLED("operation_cancelled"),
    UNEXPECTED_ERROR("unexpected_error"),
}

const val TTS_SYSTEM_VOICE_KEY = "system"

enum class TtsPreviewState {
    IDLE,
    LOADING,
    SPEAKING,
}

interface TtsController : NarrationController {

    override val isPlaying: Flow<Boolean>

    override val isLoading: Flow<Boolean>

    override val isPlaybackStartPending: Flow<Boolean>

    override val chapterCompleted: Flow<String>

    val currentSentence: Flow<TtsSentence?>

    val previewState: Flow<TtsPreviewState>

    val voicePreparationState: Flow<TtsVoicePreparationState>

    val playbackOperations: Flow<TtsPlaybackOperation>

    suspend fun hasReadableContent(): Boolean

    suspend fun availableVoices(): List<TtsVoice>

    fun selectVoice(voiceId: String?)

    fun previewVoice(voiceId: String?, text: String)

    fun stopPreview()

    suspend fun prepareVoice(
        voiceId: String?,
        updateToLatest: Boolean = false,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean

    suspend fun deleteNeuralVoicePackage(
        voicePackage: NeuralVoicePackage,
    ): Boolean

    suspend fun enableSentencePlayback(): Boolean

    fun disableSentencePlayback()

    override fun playFromSentence(fragmentId: String, chapterHref: String?)

    override fun playFromChapterStart(chapterHref: String)

    override fun togglePlayback()

    fun stop()

    fun setRate(rate: Float)

    fun setPitch(pitch: Float)

    fun skipToNextSentence()

    fun skipToPreviousSentence()

    override fun setPlaybackSpeed(speed: Float) {
        setRate(speed)
    }

    override fun skipForward() {
        skipToNextSentence()
    }

    override fun skipBackward() {
        skipToPreviousSentence()
    }
}
