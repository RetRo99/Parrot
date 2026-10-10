package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.navigator.FinishedTtsSentence
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `a book with recorded narration keeps it and still offers the device voice`() = runTest {
        val controller = FakeTtsController(hasText = true)
        val recorded = RecordedSetup()
        val setup = setup(controller, recorded, savedVoiceId = KOKORO_VOICE.id)

        setup.run(keepNarrationActive = true)

        assertFalse(recorded.didTakeOverNarration, "narration must keep the play button")
        assertEquals(0, recorded.sentencePlaybackEnables)
        assertEquals(listOf(SYSTEM_VOICE, KOKORO_VOICE), recorded.voices)
        assertEquals(KOKORO_VOICE.id, recorded.selectedVoiceId)
        assertTrue(recorded.isReadAloudAvailable, "read-aloud should be available")
        assertEquals(1, recorded.collectorStarts)
        // The device voice is offered, so its pack is loaded ahead of the first tap.
        assertEquals(listOf(KOKORO_VOICE.id), recorded.preparedVoiceIds)
    }

    // TTS-F23: most Project Gutenberg books open on a cover or a title page.

    @Test
    fun `a first page with no text still offers the voices and the saved one`() = runTest {
        val controller = FakeTtsController(hasText = false)
        val recorded = RecordedSetup()
        val setup = setup(controller, recorded, savedVoiceId = KOKORO_VOICE.id)

        backgroundScope.launch { setup.run(keepNarrationActive = false) }
        runCurrent()

        assertEquals(listOf(SYSTEM_VOICE, KOKORO_VOICE), recorded.voices)
        assertEquals(KOKORO_VOICE.id, recorded.selectedVoiceId)
        assertEquals(listOf<String?>(KOKORO_VOICE.id), controller.selectedVoiceIds.toList())
        assertTrue(recorded.didTakeOverNarration, "a play press must reach read-aloud")
    }

    @Test
    fun `a locator move into text makes read-aloud available without reopening`() = runTest {
        val controller = FakeTtsController(hasText = false)
        val recorded = RecordedSetup()
        val locators = MutableStateFlow(locatorAt(FIRST_HREF))
        val setup = setup(controller, recorded, savedVoiceId = null, locators = locators)

        backgroundScope.launch { setup.run(keepNarrationActive = false) }
        runCurrent()
        assertFalse(recorded.isReadAloudAvailable, "the title page is not read-aloud's answer")

        controller.hasText = true
        locators.value = locatorAt("chapter-1.xhtml")
        runCurrent()

        assertTrue(recorded.isReadAloudAvailable, "read-aloud should be available")
        assertEquals(1, recorded.sentencePlaybackEnables)
        assertEquals(1, recorded.collectorStarts)
    }

    @Test
    fun `a book with no text anywhere never becomes available and collects once`() = runTest {
        val controller = FakeTtsController(hasText = false)
        val recorded = RecordedSetup()
        val locators = MutableStateFlow(locatorAt(FIRST_HREF))
        val setup = setup(controller, recorded, savedVoiceId = null, locators = locators)

        backgroundScope.launch { setup.run(keepNarrationActive = false) }
        runCurrent()
        locators.value = locatorAt("plate-1.xhtml")
        runCurrent()
        locators.value = locatorAt("plate-2.xhtml")
        runCurrent()

        assertFalse(recorded.isReadAloudAvailable, "no page of this book can be read aloud")
        assertEquals(0, recorded.sentencePlaybackEnables)
        assertEquals(1, recorded.collectorStarts)
    }

    @Test
    fun `availability turning on late gives one callback per finished sentence`() = runTest {
        val controller = FakeTtsController(hasText = false)
        val finished = mutableListOf<FinishedTtsSentence>()
        val recorded = RecordedSetup(
            // Stands in for the ViewModel's collectors: the recap capture is fed from here.
            onStartCollectors = {
                backgroundScope.launch {
                    controller.finishedSentences.collect { sentence -> finished += sentence }
                }
            },
        )
        val locators = MutableStateFlow(locatorAt(FIRST_HREF))
        val setup = setup(controller, recorded, savedVoiceId = null, locators = locators)

        backgroundScope.launch { setup.run(keepNarrationActive = false) }
        runCurrent()
        controller.hasText = true
        locators.value = locatorAt("chapter-1.xhtml")
        runCurrent()
        controller.hasText = false
        locators.value = locatorAt("plate-1.xhtml")
        runCurrent()
        controller.hasText = true
        locators.value = locatorAt("chapter-2.xhtml")
        runCurrent()

        controller.finishedSentences.emit(finishedSentence(0))
        controller.finishedSentences.emit(finishedSentence(1))
        runCurrent()

        assertEquals(1, recorded.collectorStarts)
        assertEquals(listOf(0, 1), finished.map { it.sentence.index })
    }

    private fun finishedSentence(index: Int) = FinishedTtsSentence(
        chapterHref = "chapter-1.xhtml",
        sentence = TtsSentence(index = index, elementId = "sentence-$index", text = "Text."),
    )

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

internal class RecordedSetup(private val onStartCollectors: () -> Unit = {}) {
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
        startCollectors = {
            collectorStarts++
            onStartCollectors()
        },
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
    override val finishedSentences = MutableSharedFlow<FinishedTtsSentence>(
        extraBufferCapacity = 8,
    )
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
