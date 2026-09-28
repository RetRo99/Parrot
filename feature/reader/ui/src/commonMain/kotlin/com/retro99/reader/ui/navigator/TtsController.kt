package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsSentence
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.reader.ui.tts.TtsVoicePreparationState
import kotlinx.coroutines.flow.Flow

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
