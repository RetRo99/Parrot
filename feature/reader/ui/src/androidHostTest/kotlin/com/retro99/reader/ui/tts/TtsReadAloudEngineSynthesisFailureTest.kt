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
 * TTS-F01 and TTS-F02: a sentence that fails synthesis on a path the user did not start.
 * Sentence 0 plays, sentence 1 fails — once by returning an error result, once by
 * throwing — and the engine is pushed onto it by each of the four entry paths that do not
 * go through `AndroidTtsController.requestPlayback`.
 *
 * Each case wants: nothing escapes the engine's scope, exactly one
 * `PlaybackFailure(SYNTHESIS_FAILED)` on `playbackFailures`, and the engine stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsReadAloudEngineSynthesisFailureTest {

    private enum class FailureMode { ERROR_RESULT, THROWN }

    private val audioDirectory: File = Files.createTempDirectory("tts-engine-failure").toFile()
    private val player = FakeTtsEnginePlayer()
    private val playerProvider = FakeTtsEnginePlayerProvider(player)
    private val audioSource = FakeSentenceAudioSource(audioDirectory)
    private val mediaPlaybackController by lazy { MediaPlaybackController() }

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
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        engine?.close()
        Dispatchers.resetMain()
        audioDirectory.deleteRecursively()
    }

    @Test
    fun `auto advance onto a sentence whose synthesis errors reports one failure`() =
        runAutoAdvanceCase(FailureMode.ERROR_RESULT)

    @Test
    fun `auto advance onto a sentence whose synthesis throws reports one failure`() =
        runAutoAdvanceCase(FailureMode.THROWN)

    @Test
    fun `skip to next onto a sentence whose synthesis errors reports one failure`() =
        runSkipToNextCase(FailureMode.ERROR_RESULT)

    @Test
    fun `skip to next onto a sentence whose synthesis throws reports one failure`() =
        runSkipToNextCase(FailureMode.THROWN)

    @Test
    fun `skip to previous onto a sentence whose synthesis errors reports one failure`() =
        runSkipToPreviousCase(FailureMode.ERROR_RESULT)

    @Test
    fun `skip to previous onto a sentence whose synthesis throws reports one failure`() =
        runSkipToPreviousCase(FailureMode.THROWN)

    @Test
    fun `seeking onto a sentence whose synthesis errors reports one failure`() =
        runChapterSeekCase(FailureMode.ERROR_RESULT)

    @Test
    fun `seeking onto a sentence whose synthesis throws reports one failure`() =
        runChapterSeekCase(FailureMode.THROWN)

    /**
     * A user start that fails must stay a thrown [TtsPlaybackStartException] and emit
     * nothing on `playbackFailures`, so the user sees one Failed outcome and not two.
     * `AndroidTtsController.requestPlayback` turns the throw into one Failed
     * (`AndroidTtsController.kt:689-713`), and its `playbackFailures` collector
     * (`:240-263`) would turn any event into a second one: when the catch has already
     * cleared the attempt, that collector takes its `else` branch and emits an
     * ACTIVE_PLAYBACK Failed of its own.
     */
    @Test
    fun `a user start whose synthesis fails throws and emits no failure event`() = runTest {
        val engine = createEngine()
        val failures = collectFailures(engine)
        audioSource.failingTexts += sentences[0].text
        engine.setSentences(sentences)

        var thrown: Throwable? = null
        launch {
            try {
                engine.playFrom(
                    index = 0,
                    voiceId = VOICE_ID,
                    rate = 1f,
                    pitch = 1f,
                    completeChapterOnEnd = true,
                    showPlaybackNotification = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                thrown = error
            }
        }
        advanceUntilIdle()

        assertEquals(
            TtsPlaybackFailureReason.SYNTHESIS_FAILED,
            (thrown as? TtsPlaybackStartException)?.reasonCode,
            "expected a TtsPlaybackStartException out of playFrom, got $thrown",
        )
        assertEquals(emptyList(), failures, "the user-start path must emit no failure event")
        assertEquals(emptyList(), uncaught.map { error -> error.toString() })
        assertEquals(-1, engine.currentSentenceIndex)
        assertEquals(false, engine.isLoading.value)
    }

    private fun runAutoAdvanceCase(mode: FailureMode) = runTest {
        val engine = createEngine()
        val failures = collectFailures(engine)
        failSecondSentence(mode)
        engine.setSentences(sentences)
        startAt(engine, index = 0)

        // Sentence 0 played out; the engine advances to sentence 1 on its own.
        player.finishCurrentItem()
        advanceUntilIdle()

        assertOneSynthesisFailure(engine, failures)
    }

    private fun runSkipToNextCase(mode: FailureMode) = runTest {
        val engine = createEngine()
        val failures = collectFailures(engine)
        failSecondSentence(mode)
        engine.setSentences(sentences)
        startAt(engine, index = 0)

        engine.skipToNextSentence()
        advanceUntilIdle()

        assertOneSynthesisFailure(engine, failures)
    }

    private fun runSkipToPreviousCase(mode: FailureMode) = runTest {
        val engine = createEngine()
        val failures = collectFailures(engine)
        failSecondSentence(mode)
        engine.setSentences(sentences)
        startAt(engine, index = 2)

        engine.skipToPreviousSentence()
        advanceUntilIdle()

        assertOneSynthesisFailure(engine, failures)
    }

    private fun runChapterSeekCase(mode: FailureMode) = runTest {
        val engine = createEngine()
        val failures = collectFailures(engine)
        failSecondSentence(mode)
        engine.setSentences(sentences)
        startAt(engine, index = 0)

        // Past the end of sentence 0 (1_000 ms once it is synthesised), inside sentence 1.
        mediaPlaybackController.requestTtsChapterPosition(SEEK_INTO_SECOND_SENTENCE_MS)
        advanceUntilIdle()

        assertOneSynthesisFailure(engine, failures)
    }

    // ---- fixture ----

    private fun failSecondSentence(mode: FailureMode) {
        val text = sentences[1].text
        when (mode) {
            FailureMode.ERROR_RESULT -> audioSource.failingTexts += text
            FailureMode.THROWN -> audioSource.throwingTexts += text
        }
    }

    private fun assertOneSynthesisFailure(
        engine: TtsReadAloudEngine,
        failures: List<TtsReadAloudEngine.PlaybackFailure>,
    ) {
        assertEquals(
            emptyList(),
            uncaught.map { error -> error.toString() },
            "an exception escaped the engine's scope",
        )
        assertEquals(1, failures.size, "expected exactly one playback failure, got $failures")
        assertEquals(TtsPlaybackFailureReason.SYNTHESIS_FAILED, failures.single().reasonCode)
        assertEquals(CORRELATION_ID, failures.single().correlationId)
        assertEquals(-1, engine.currentSentenceIndex)
        assertNull(engine.currentSentence.value)
        assertEquals(false, engine.isLoading.value)
        assertEquals(false, engine.isPlaying.value)
    }

    private fun TestScope.createEngine(): TtsReadAloudEngine =
        TtsReadAloudEngine(
            synthesizer = FakeTtsSynthesizer(),
            audioGenerator = audioSource,
            mediaPlaybackController = mediaPlaybackController,
            playerProvider = playerProvider,
            mainContext = StandardTestDispatcher(testScheduler) +
                    CoroutineExceptionHandler { _, error -> uncaught += error },
        ).also { created ->
            created.setPlaybackOperationCorrelationId(CORRELATION_ID)
            engine = created
        }

    /** A user start that succeeds, with the player reporting playback as it would. */
    private fun TestScope.startAt(engine: TtsReadAloudEngine, index: Int) {
        launch {
            try {
                engine.playFrom(
                    index = index,
                    voiceId = VOICE_ID,
                    rate = 1f,
                    pitch = 1f,
                    completeChapterOnEnd = true,
                    showPlaybackNotification = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw AssertionError("the user start itself failed", error)
            }
        }
        advanceUntilIdle()
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

    private companion object {
        const val VOICE_ID = "en-us"
        const val CORRELATION_ID = "correlation-1"
        const val SEEK_INTO_SECOND_SENTENCE_MS = 1_500L
    }
}
