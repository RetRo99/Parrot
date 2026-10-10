package com.retro99.reader.ui.tts

import com.retro99.reader.ui.playback.MediaPlaybackController
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * TTS-F16: which chapter a completion belongs to when the reader's locator has already
 * moved on. The attempt to produce the race is
 * `a chapter abandoned by a locator move does not complete at all`; see the finding in
 * `docs/tts-investigation.md`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsReadAloudEngineChapterCompletionTest {

    private val audioDirectory: File = Files.createTempDirectory("tts-chapter").toFile()
    private val player = FakeTtsEnginePlayer()
    private val playerProvider = FakeTtsEnginePlayerProvider(player)
    private val audioSource = FakeSentenceAudioSource(audioDirectory)

    private val uncaught = mutableListOf<Throwable>()

    private var engine: TtsReadAloudEngine? = null

    private val chapterOne = (0..2).map { index ->
        TtsSentence(index = index, elementId = "one-$index", text = "Chapter one, $index.")
    }

    private val chapterTwo = (0..2).map { index ->
        TtsSentence(index = index, elementId = "two-$index", text = "Chapter two, $index.")
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        engine?.close()
        Dispatchers.resetMain()
        audioDirectory.deleteRecursively()
    }

    @Test
    fun `the chapter completes once, for the sentences that were playing`() = runTest {
        val engine = createEngine()
        val completions = collectChapterCompletions(engine)
        engine.setSentences(chapterOne)
        start(engine, index = 0)

        repeat(chapterOne.size) {
            player.finishCurrentItem()
            runCurrent()
        }

        assertEquals(1, completions.size)
        assertEquals(-1, engine.currentSentenceIndex)
        // The sentences the completion belongs to are still the engine's own.
        assertEquals(chapterOne.size, engine.sentenceCount.value)
        assertTrue(uncaught.isEmpty())
    }

    /**
     * The window TTS-F16 needs: the chapter's last sentence ends after the locator has
     * moved. The locator collector stops the engine on a chapter change
     * (`AndroidTtsController.kt`, the `currentLocator` collector), and a stopped engine
     * emits no completion, so the engine never reports a chapter under the next one's
     * name. What remains of the finding is the controller labelling the event with
     * `lastLocator?.href` when the collector runs, which cannot be reached from here.
     */
    @Test
    fun `a chapter abandoned by a locator move does not complete at all`() = runTest {
        val engine = createEngine()
        val completions = collectChapterCompletions(engine)
        engine.setSentences(chapterOne)
        start(engine, index = chapterOne.lastIndex)

        // The reader swiped into the next chapter: stop, then load its sentences.
        engine.stop()
        engine.setSentences(chapterTwo)
        runCurrent()

        // A player callback posted before the stop still arrives after it.
        player.finishCurrentItem()
        runCurrent()

        assertEquals(emptyList(), completions)
        // A callback queued before the stop cannot start the newly loaded chapter.
        assertEquals(-1, engine.currentSentenceIndex)
        assertTrue(uncaught.isEmpty())
    }

    // ---- fixture ----

    private fun TestScope.createEngine(): TtsReadAloudEngine =
        TtsReadAloudEngine(
            synthesizer = FakeTtsSynthesizer(),
            audioGenerator = audioSource,
            mediaPlaybackController = MediaPlaybackController(),
            playerProvider = playerProvider,
            mainContext = engineContext(),
        ).also { created -> engine = created }

    private fun TestScope.engineContext(): CoroutineContext =
        StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> uncaught += error }

    private fun TestScope.start(engine: TtsReadAloudEngine, index: Int) {
        launch {
            engine.playFrom(
                index = index,
                voiceId = VOICE_ID,
                rate = 1f,
                pitch = 1f,
                completeChapterOnEnd = true,
                showPlaybackNotification = false,
            )
        }
        runCurrent()
        player.reportPlaylistItem()
        player.reportReady()
        player.reportPlaying(true)
        runCurrent()
    }

    private fun TestScope.collectChapterCompletions(engine: TtsReadAloudEngine): List<Unit> {
        val collected = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.chapterCompleted.collect { collected += Unit }
        }
        return collected
    }

    private companion object {
        const val VOICE_ID = "en-us"
    }
}
