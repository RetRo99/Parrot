package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.navigator.TtsController
import com.retro99.reader.ui.navigator.TtsPlaybackOperation
import com.retro99.reader.ui.navigator.TtsPreviewState
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsSentence
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.reader.ui.tts.TtsVoicePreparationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTtsSetupTest {

    @Test
    fun `a first page with text offers the voices and selects the saved one`() = runTest {
        val controller = FakeTtsController(hasText = true)
        val recorded = RecordedSetup()
        val setup = setup(controller, recorded, savedVoiceId = KOKORO_VOICE.id)

        setup.run(keepNarrationActive = false)

        assertEquals(listOf(SYSTEM_VOICE, KOKORO_VOICE), recorded.voices)
        assertEquals(KOKORO_VOICE.id, recorded.selectedVoiceId)
        assertEquals(listOf<String?>(KOKORO_VOICE.id), controller.selectedVoiceIds.toList())
        assertTrue(recorded.isReadAloudAvailable, "read-aloud should be available")
        assertEquals(1, recorded.collectorStarts)
        assertTrue(recorded.didTakeOverNarration, "read-aloud should take the play button over")
        assertEquals(1, recorded.sentencePlaybackEnables)
        assertEquals(listOf(KOKORO_VOICE.id), recorded.preparedVoiceIds)
        assertEquals(0, recorded.savedVoiceIds.size)
    }

    private fun setup(
        controller: FakeTtsController,
        recorded: RecordedSetup,
        savedVoiceId: String?,
        isTtsEnabled: Boolean = true,
        hasAcceptedSupertonicTerms: Boolean = false,
        locators: Flow<LocatorState> = MutableStateFlow(locatorAt(FIRST_HREF)),
    ) = ReaderTtsSetup(
        ttsController = controller,
        locators = locators,
        readSettings = {
            ReaderTtsSetupSettings(voiceId = savedVoiceId, isTtsEnabled = isTtsEnabled)
        },
        hasAcceptedSupertonicTerms = { hasAcceptedSupertonicTerms },
        actions = recorded.actions,
    )

    private companion object {
        const val FIRST_HREF = "cover.xhtml"
        val SYSTEM_VOICE = TtsVoice(id = "system-en", name = "System", locale = "en-US")
        val KOKORO_VOICE = TtsVoice(
            id = "kokoro:af_heart",
            name = "Heart",
            locale = "en-US",
            isNeural = true,
            isDownloaded = true,
        )
    }
}

internal fun locatorAt(href: String) = LocatorState(
    href = href,
    type = "application/xhtml+xml",
    title = null,
    progression = 0.0,
    position = 1,
    totalProgression = 0.0,
    fragments = null,
)

internal class RecordedSetup {
    var voices: List<TtsVoice>? = null
        private set
    var selectedVoiceId: String? = null
        private set
    var isReadAloudAvailable = false
        private set
    var collectorStarts = 0
        private set
    var didTakeOverNarration = false
        private set
    var sentencePlaybackEnables = 0
        private set
    val preparedVoiceIds = mutableListOf<String>()
    val savedVoiceIds = mutableListOf<Pair<String?, String?>>()

    val actions = ReaderTtsSetupActions(
        takeOverNarration = { didTakeOverNarration = true },
        showVoices = { offered, selected ->
            voices = offered
            selectedVoiceId = selected
        },
        startCollectors = { collectorStarts++ },
        markReadAloudAvailable = { isReadAloudAvailable = true },
        saveSelectedVoice = { readVoiceId, selected ->
            savedVoiceIds += readVoiceId to selected
        },
        enableSentencePlayback = { sentencePlaybackEnables++ },
        prepareVoice = { voiceId -> preparedVoiceIds += voiceId },
    )
}

internal class FakeTtsController(
    var hasText: Boolean = true,
    private val voices: List<TtsVoice> = listOf(
        TtsVoice(id = "system-en", name = "System", locale = "en-US"),
        TtsVoice(
            id = "kokoro:af_heart",
            name = "Heart",
            locale = "en-US",
            isNeural = true,
            isDownloaded = true,
        ),
    ),
) : TtsController {

    override val isPlaying: Flow<Boolean> = flowOf(false)
    override val isLoading: Flow<Boolean> = flowOf(false)
    override val isPlaybackStartPending: Flow<Boolean> = flowOf(false)
    override val chapterCompleted: Flow<String> = emptyFlow()
    override val currentSentence = MutableStateFlow<TtsSentence?>(null)
    override val previewState: Flow<TtsPreviewState> = flowOf(TtsPreviewState.IDLE)
    override val voicePreparationState: Flow<TtsVoicePreparationState> =
        flowOf(TtsVoicePreparationState.Idle)
    override val playbackOperations: Flow<TtsPlaybackOperation> =
        MutableSharedFlow(extraBufferCapacity = 8)

    val selectedVoiceIds = mutableListOf<String?>()
    var readableContentChecks = 0
        private set

    override suspend fun hasReadableContent(): Boolean {
        readableContentChecks++
        return hasText
    }

    override suspend fun availableVoices(): List<TtsVoice> = voices

    override fun selectVoice(voiceId: String?) {
        selectedVoiceIds += voiceId
    }

    override fun previewVoice(voiceId: String?, text: String) = Unit
    override fun stopPreview() = Unit

    override suspend fun prepareVoice(
        voiceId: String?,
        updateToLatest: Boolean,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean = true

    override suspend fun deleteNeuralVoicePackage(voicePackage: NeuralVoicePackage): Boolean = true

    override suspend fun enableSentencePlayback(): Boolean = hasText

    override fun disableSentencePlayback() = Unit
    override fun playFromSentence(fragmentId: String, chapterHref: String?) = Unit
    override fun playFromChapterStart(chapterHref: String) = Unit
    override fun togglePlayback() = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun setRate(rate: Float) = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun skipToNextSentence() = Unit
    override fun skipToPreviousSentence() = Unit
    override fun close() = Unit
}
