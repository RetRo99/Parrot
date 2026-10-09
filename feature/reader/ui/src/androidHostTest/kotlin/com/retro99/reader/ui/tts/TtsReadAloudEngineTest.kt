package com.retro99.reader.ui.tts

import com.retro99.reader.ui.navigator.TtsPlaybackFailureReason
import com.retro99.reader.ui.playback.MediaPlaybackController
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * What [TtsReadAloudEngine] does today, pinned before anything is changed. The engine is
 * driven through the player seam, so no Android runtime is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsReadAloudEngineTest {

    private val audioDirectory: File = Files.createTempDirectory("tts-engine").toFile()
    private val player = FakeTtsEnginePlayer()
    private val playerProvider = FakeTtsEnginePlayerProvider(player)
    private val audioSource = FakeSentenceAudioSource(audioDirectory)

    /** Everything that escaped the engine's own scope. */
    private val uncaught = mutableListOf<Throwable>()

    private var engine: TtsReadAloudEngine? = null

    private val sentences = listOf(
        TtsSentence(index = 0, elementId = "p0", text = "Sentence zero."),
        TtsSentence(index = 1, elementId = "p1", text = "Sentence one."),
        TtsSentence(index = 2, elementId = "p2", text = "Sentence two."),
    )

    @BeforeTest
    fun setUp() {
        // MediaPlaybackController builds a main-dispatcher scope of its own.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        engine?.close()
        Dispatchers.resetMain()
        audioDirectory.deleteRecursively()
    }

    @Test
    fun `playing from the first sentence makes it current and playing`() = runTest {
        val engine = createEngine()
        engine.setSentences(sentences)

        val failure = userStart(engine, index = 0)
        startedPlaying()

        assertNull(failure)
        assertEquals(sentences[0], engine.currentSentence.value)
        assertEquals(0, engine.currentSentenceIndex)
        assertEquals(3, engine.sentenceCount.value)
        assertTrue(engine.isPlaying.value)
        assertEquals(false, engine.isLoading.value)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `a sentence playing out advances to the next sentence`() = runTest {
        val engine = createEngine()
        val finished = collectFinished(engine)
        engine.setSentences(sentences)
        userStart(engine, index = 0)
        startedPlaying()

        player.finishCurrentItem()
        advanceUntilIdle()

        assertEquals(1, engine.currentSentenceIndex)
        assertEquals(sentences[1], engine.currentSentence.value)
        assertEquals(listOf(sentences[0]), finished)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `the last sentence ending completes the chapter once and stops`() = runTest {
        val engine = createEngine()
        val chapterCompletions = collectChapterCompletions(engine)
        engine.setSentences(sentences)
        userStart(engine, index = 0)
        startedPlaying()

        repeat(sentences.size) {
            player.finishCurrentItem()
            advanceUntilIdle()
        }

        assertEquals(1, chapterCompletions.size)
        assertNull(engine.currentSentence.value)
        assertEquals(-1, engine.currentSentenceIndex)
        assertEquals(false, engine.isPlaying.value)
        assertEquals(false, engine.isLoading.value)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `pause then resume keeps the sentence index`() = runTest {
        val engine = createEngine()
        engine.setSentences(sentences)
        userStart(engine, index = 1)
        startedPlaying()

        engine.pause()
        player.reportPlaying(false)

        assertEquals(1, engine.currentSentenceIndex)
        assertEquals(sentences[1], engine.currentSentence.value)
        assertEquals(false, engine.isPlaying.value)

        engine.resume()
        player.reportPlaying(true)

        assertEquals(1, engine.currentSentenceIndex)
        assertTrue(engine.isPlaying.value)
        assertTrue(player.commands.contains("pause"))
        assertEquals(2, player.commands.count { it == "play" })
    }

    @Test
    fun `stop clears the current sentence and index`() = runTest {
        val engine = createEngine()
        engine.setSentences(sentences)
        userStart(engine, index = 0)
        startedPlaying()

        engine.stop()
        advanceUntilIdle()

        assertNull(engine.currentSentence.value)
        assertEquals(-1, engine.currentSentenceIndex)
        assertEquals(false, engine.isPlaying.value)
        assertEquals(false, engine.isLoading.value)
        assertEquals(0, engine.currentSentenceDurationMs.value)
        // The sentence list itself survives a stop.
        assertEquals(3, engine.sentenceCount.value)
    }

    @Test
    fun `a second play request while the first is synthesising wins`() = runTest {
        val engine = createEngine()
        engine.setSentences(sentences)

        launch { runCatching { play(engine, index = 0) } }
        launch { runCatching { play(engine, index = 2) } }
        advanceUntilIdle()
        startedPlaying()

        assertEquals(2, engine.currentSentenceIndex)
        assertEquals(sentences[2], engine.currentSentence.value)
        val lastPlaylist = player.commands.last { it.startsWith("setItems:") }
        assertEquals("setItems:tts::2", lastPlaylist)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `skip to next sentence plays the next one`() = runTest {
        val engine = createEngine()
        engine.setSentences(sentences)
        userStart(engine, index = 0)
        startedPlaying()

        engine.skipToNextSentence()
        advanceUntilIdle()

        assertEquals(1, engine.currentSentenceIndex)
        assertEquals(sentences[1], engine.currentSentence.value)
        // Sentence 2 is already synthesised, so it is queued behind the skip target.
        assertEquals(
            "setItems:tts::1,tts::2",
            player.commands.last { it.startsWith("setItems:") },
        )
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `skip to next on the last sentence completes the chapter and stops`() = runTest {
        val engine = createEngine()
        val chapterCompletions = collectChapterCompletions(engine)
        engine.setSentences(sentences)
        userStart(engine, index = 2)
        startedPlaying()

        engine.skipToNextSentence()
        advanceUntilIdle()

        assertEquals(1, chapterCompletions.size)
        assertEquals(-1, engine.currentSentenceIndex)
        assertNull(engine.currentSentence.value)
    }

    @Test
    fun `skip to previous plays the previous sentence, and does nothing at the start`() = runTest {
        val engine = createEngine()
        engine.setSentences(sentences)
        userStart(engine, index = 1)
        startedPlaying()

        engine.skipToPreviousSentence()
        advanceUntilIdle()

        assertEquals(0, engine.currentSentenceIndex)
        assertEquals(
            "setItems:tts::0,tts::1,tts::2",
            player.commands.last { it.startsWith("setItems:") },
        )

        val commandsBefore = player.commands.toList()
        engine.skipToPreviousSentence()
        advanceUntilIdle()

        assertEquals(0, engine.currentSentenceIndex)
        assertEquals(commandsBefore, player.commands)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `a player error emits one player failure and stops`() = runTest {
        val engine = createEngine()
        val failures = collectFailures(engine)
        engine.setPlaybackOperationCorrelationId(CORRELATION_ID)
        engine.setSentences(sentences)
        userStart(engine, index = 0)
        startedPlaying()

        val error = IllegalStateException("decoder died")
        player.reportError(error)
        advanceUntilIdle()

        assertEquals(1, failures.size)
        assertEquals(TtsPlaybackFailureReason.PLAYER_ERROR, failures.single().reasonCode)
        assertEquals(CORRELATION_ID, failures.single().correlationId)
        assertEquals(error, failures.single().error)
        assertEquals(-1, engine.currentSentenceIndex)
        assertEquals(false, engine.isPlaying.value)
        assertEquals(false, engine.isLoading.value)
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

    /** A user playback request, the way `AndroidTtsController.requestPlayback` makes it. */
    private suspend fun play(engine: TtsReadAloudEngine, index: Int) {
        engine.playFrom(
            index = index,
            voiceId = VOICE_ID,
            rate = 1f,
            pitch = 1f,
            completeChapterOnEnd = true,
            showPlaybackNotification = false,
        )
    }

    /** Runs a user start to completion; returns what it threw, as the controller sees it. */
    private fun TestScope.userStart(engine: TtsReadAloudEngine, index: Int): Throwable? {
        var failure: Throwable? = null
        launch {
            try {
                play(engine, index)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failure = error
            }
        }
        advanceUntilIdle()
        return failure
    }

    /** What the player reports once a playlist has been handed to it. */
    private fun startedPlaying() {
        player.reportPlaylistItem()
        player.reportReady()
        player.reportPlaying(true)
    }

    private fun TestScope.collectFailures(
        engine: TtsReadAloudEngine,
    ): List<TtsReadAloudEngine.PlaybackFailure> {
        val collected = mutableListOf<TtsReadAloudEngine.PlaybackFailure>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.playbackFailures.collect { failure -> collected += failure }
        }
        return collected
    }

    private fun TestScope.collectChapterCompletions(engine: TtsReadAloudEngine): List<Unit> {
        val collected = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.chapterCompleted.collect { collected += Unit }
        }
        return collected
    }

    private fun TestScope.collectFinished(engine: TtsReadAloudEngine): List<TtsSentence> {
        val collected = mutableListOf<TtsSentence>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.finishedSentences.collect { sentence -> collected += sentence }
        }
        return collected
    }

    private companion object {
        const val VOICE_ID = "en-us"
        const val CORRELATION_ID = "correlation-1"
    }
}
