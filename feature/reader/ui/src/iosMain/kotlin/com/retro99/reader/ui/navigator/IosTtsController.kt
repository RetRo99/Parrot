package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsSentence
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.reader.ui.tts.TtsVoicePreparationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * Device read-aloud isn't built on iOS yet, so there are no sentence
 * callbacks and recaps capture iOS reading from settled pages only.
 */
@Scope(ReaderScope::class)
@Scoped(binds = [TtsController::class])
class IosTtsController : TtsController {

    override val isPlaying: Flow<Boolean> = flowOf(false)

    override val isLoading: Flow<Boolean> = flowOf(false)

    override val isPlaybackStartPending: Flow<Boolean> = flowOf(false)

    override val chapterCompleted: Flow<String> = emptyFlow()

    override val currentSentence: Flow<TtsSentence?> = flowOf(null)

    override val previewState: Flow<TtsPreviewState> = flowOf(TtsPreviewState.IDLE)

    override val voicePreparationState: Flow<TtsVoicePreparationState> =
        flowOf(TtsVoicePreparationState.Idle)

    override val playbackOperations: Flow<TtsPlaybackOperation> = emptyFlow()

    override suspend fun hasReadableContent(): Boolean = false

    override suspend fun availableVoices(): List<TtsVoice> = emptyList()

    override fun selectVoice(voiceId: String?) = Unit

    override fun previewVoice(voiceId: String?, text: String) = Unit

    override fun stopPreview() = Unit

    override suspend fun prepareVoice(
        voiceId: String?,
        updateToLatest: Boolean,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean = false

    override suspend fun deleteNeuralVoicePackage(
        voicePackage: NeuralVoicePackage,
    ): Boolean = false

    override suspend fun enableSentencePlayback(): Boolean = false

    override fun disableSentencePlayback() = Unit

    override fun playFromSentence(fragmentId: String, chapterHref: String?) = Unit

    override fun playFromChapterStart(chapterHref: String) = Unit

    override fun togglePlayback() = Unit

    override fun stop() = Unit

    override fun setRate(rate: Float) = Unit

    override fun setPitch(pitch: Float) = Unit

    override fun skipToNextSentence() = Unit

    override fun skipToPreviousSentence() = Unit

    override fun close() = Unit
}
