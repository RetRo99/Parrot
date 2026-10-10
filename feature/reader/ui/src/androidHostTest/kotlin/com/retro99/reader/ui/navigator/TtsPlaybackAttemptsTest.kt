package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.tts.FakeSentenceAudioSource
import com.retro99.reader.ui.tts.FakeTtsEnginePlayer
import com.retro99.reader.ui.tts.FakeTtsEnginePlayerProvider
import com.retro99.reader.ui.tts.FakeTtsSynthesizer
import com.retro99.reader.ui.tts.TtsReadAloudEngine
import com.retro99.reader.ui.tts.TtsSentence
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [TtsPlaybackAttempts] drives a real [TtsReadAloudEngine] behind the run 2a player seam,
 * so a start is tracked against real synthesis rather than a stand-in. The controller's
 * own Android dependencies are not involved; this is the testable half of
 * `AndroidTtsController.restartForSettingsChange` and its attempt bookkeeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsPlaybackAttemptsTest {

    private val audioDirectory: File = Files.createTempDirectory("tts-attempts").toFile()
    private val player = FakeTtsEnginePlayer()
    private val playerProvider = FakeTtsEnginePlayerProvider(player)
    private val audioSource = FakeSentenceAudioSource(audioDirectory)

    /** Everything that escaped a scope instead of becoming an outcome. */
    private val uncaught = mutableListOf<Throwable>()

    private var engine: TtsReadAloudEngine? = null
    private val scopes = mutableListOf<CoroutineScope>()

    private val sentences = (0..4).map { index ->
        TtsSentence(index = index, elementId = "p$index", text = "Sentence $index.")
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        engine?.close()
        Dispatchers.resetMain()
        audioDirectory.deleteRecursively()
    }

    @Test
    fun `a plain user start reports one attempt and one success`() = runTest {
        val session = startedSession(fromIndex = 0)

        assertEquals(
            listOf("attempted:controls", "succeeded:controls"),
            session.outcomes,
        )
        assertEquals(false, session.attempts.isStartPending.value)
        assertTrue(uncaught.isEmpty())
    }

    // ---- TTS-F06: a setting changed while narration is paused ----

    @Test
    fun `a speed change while paused plays the paused sentence again at the new speed`() = runTest {
        val session = pausedOnSentenceThree()
        audioSource.requests.clear()

        session.setRate(1.5f)
        runCurrent()
        session.togglePlayback()
        runCurrent()
        startedPlaying()
        runCurrent()

        assertEquals(3, session.engine.currentSentenceIndex)
        assertEquals(sentences[3], session.engine.currentSentence.value)
        assertEquals("Sentence 3.", audioSource.requests.firstOrNull()?.text)
        assertTrue(
            audioSource.requests.isNotEmpty() && audioSource.requests.all { it.rate == 1.5f },
            "synthesis after the change: ${audioSource.requests}",
        )
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `a voice change while paused plays the paused sentence again in the new voice`() = runTest {
        val session = pausedOnSentenceThree()
        audioSource.requests.clear()

        session.selectVoice(OTHER_VOICE_ID)
        runCurrent()
        session.togglePlayback()
        runCurrent()
        startedPlaying()
        runCurrent()

        assertEquals(3, session.engine.currentSentenceIndex)
        assertEquals(sentences[3], session.engine.currentSentence.value)
        assertEquals("Sentence 3.", audioSource.requests.firstOrNull()?.text)
        assertTrue(
            audioSource.requests.isNotEmpty() &&
                    audioSource.requests.all { it.voiceId == OTHER_VOICE_ID },
            "synthesis after the change: ${audioSource.requests}",
        )
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `a pitch change while paused plays the paused sentence again at the new pitch`() = runTest {
        val session = pausedOnSentenceThree()
        audioSource.requests.clear()

        session.setPitch(1.4f)
        runCurrent()
        session.togglePlayback()
        runCurrent()
        startedPlaying()
        runCurrent()

        assertEquals(3, session.engine.currentSentenceIndex)
        assertEquals(sentences[3], session.engine.currentSentence.value)
        assertEquals("Sentence 3.", audioSource.requests.firstOrNull()?.text)
        assertTrue(
            audioSource.requests.isNotEmpty() && audioSource.requests.all { it.pitch == 1.4f },
            "synthesis after the change: ${audioSource.requests}",
        )
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `the paused sentence survives the change itself, before play is pressed`() = runTest {
        val session = pausedOnSentenceThree()

        session.setRate(1.5f)
        runCurrent()

        assertEquals(3, session.engine.currentSentenceIndex)
        assertEquals(sentences[3], session.engine.currentSentence.value)
        assertEquals(sentences.size, session.engine.sentenceCount.value)
    }

    @Test
    fun `the play after a paused change reports one resume attempt and one success`() = runTest {
        val session = pausedOnSentenceThree()
        session.setRate(1.5f)
        runCurrent()
        session.clearRecorded()

        session.togglePlayback()
        runCurrent()
        startedPlaying()
        runCurrent()

        assertEquals(listOf("attempted:resume", "succeeded:resume"), session.outcomes)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `a settings change with nothing loaded does nothing`() = runTest {
        val session = newSession()

        session.setRate(1.5f)
        runCurrent()

        assertEquals(-1, session.engine.currentSentenceIndex)
        assertEquals(emptyList(), session.outcomes)
        assertEquals(emptyList(), audioSource.requests)
        assertTrue(player.commands.isEmpty(), "player commands: ${player.commands}")
    }

    // ---- TTS-F07: a setting changed while narration plays ----

    @Test
    fun `a speed change while playing is one tracked operation`() = runTest {
        val session = startedSession(fromIndex = 0)
        session.clearRecorded()

        session.setRate(1.5f)
        assertTrue(
            session.attempts.isStartPending.value,
            "the restart is not pending while it runs",
        )

        runCurrent()
        startedPlaying()
        runCurrent()

        assertEquals(
            listOf("attempted:settings_change", "succeeded:settings_change"),
            session.outcomes,
        )
        assertEquals(false, session.attempts.isStartPending.value)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `a voice change while playing whose synthesis fails reports one failure`() = runTest {
        val session = startedSession(fromIndex = 0)
        session.clearRecorded()

        // The new voice cannot synthesise anything: its pack is gone, or it errors.
        audioSource.failingTexts += sentences.map { sentence -> sentence.text }
        session.selectVoice(OTHER_VOICE_ID)
        runCurrent()

        assertEquals(
            listOf("attempted:settings_change", "failed:synthesis_failed:settings_change"),
            session.outcomes,
        )
        assertTrue(uncaught.isEmpty(), "escaped the scope: $uncaught")
    }

    @Test
    fun `two speed changes in quick succession give each attempt one terminal outcome`() = runTest {
        val session = startedSession(fromIndex = 0)
        session.clearRecorded()

        session.setRate(1.2f)
        session.setRate(1.5f)
        runCurrent()
        startedPlaying()
        runCurrent()

        val terminalsPerAttempt = session.operations
            .filter { operation -> operation !is TtsPlaybackOperation.Attempted }
            .groupingBy { operation -> operation.correlationId }
            .eachCount()
        assertTrue(
            terminalsPerAttempt.isNotEmpty() && terminalsPerAttempt.values.all { it == 1 },
            "terminal outcomes per attempt: $terminalsPerAttempt, outcomes ${session.outcomes}",
        )
        assertEquals(
            listOf(
                "attempted:settings_change",
                "cancelled:operation_cancelled:settings_change",
                "attempted:settings_change",
                "succeeded:settings_change",
            ),
            session.outcomes,
        )
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `the start deadline fires for a restart that never becomes active`() = runTest {
        val session = startedSession(fromIndex = 0)
        session.clearRecorded()
        audioSource.neverCompletingTexts += sentences[0].text

        session.setRate(1.5f)
        // The player stops the old audio for the new setting and reports it.
        player.reportPlaying(false)
        runCurrent()
        assertTrue(session.attempts.isStartPending.value)

        advanceTimeBy(START_TIMEOUT_MS + 1)

        assertEquals(
            listOf("attempted:settings_change", "failed:start_timeout:settings_change"),
            session.outcomes,
        )
        assertEquals(false, session.attempts.isStartPending.value)
        assertTrue(uncaught.isEmpty())
    }

    // ---- fixture ----

    /**
     * A controller stand-in: it owns the settings the user has chosen and performs the
     * engine half of `AndroidTtsController.startPlayback` / `restartAtIndex`.
     */
    private inner class Session(
        val engine: TtsReadAloudEngine,
        private val scope: CoroutineScope,
    ) {
        /** Every playback operation the attempts reported, in order. */
        val outcomes = mutableListOf<String>()
        val operations = mutableListOf<TtsPlaybackOperation>()

        fun clearRecorded() {
            outcomes.clear()
            operations.clear()
        }

        /** The settings the user has chosen, as the controller's own fields hold them. */
        private var chosenVoiceId: String? = VOICE_ID
        private var chosenRate: Float = 1f
        private var chosenPitch: Float = 1f

        val attempts: TtsPlaybackAttempts = TtsPlaybackAttempts(
            scope = scope,
            startupTimeoutMs = START_TIMEOUT_MS,
            engine = engine,
            nowMs = { 0L },
            restartAtIndex = { index -> restartAtIndex(index) },
        )

        /** Mirrors the controller's user-start path: sentences, then a fresh playFrom. */
        fun userStart(index: Int) {
            attempts.request(TtsPlaybackAction.CONTROLS) { attempt ->
                freshStart(attempt, index)
            }
        }

        /** `AndroidTtsController.togglePlayback`. */
        fun togglePlayback() {
            if (engine.isPlaying.value) {
                engine.pause()
                player.reportPlaying(false)
                return
            }
            attempts.onPlayPressed(
                resume = { attempt ->
                    attempts.armStartupTimeout(attempt)
                    engine.resume()
                    null
                },
                freshStart = { attempt -> freshStart(attempt, index = null) },
            )
        }

        /** `AndroidTtsController.setRate`. */
        fun setRate(rate: Float) {
            chosenRate = rate
            attempts.onSettingsChanged()
        }

        /** `AndroidTtsController.setPitch`. */
        fun setPitch(pitch: Float) {
            chosenPitch = pitch
            attempts.onSettingsChanged()
        }

        /** `AndroidTtsController.selectVoice`. */
        fun selectVoice(voiceId: String?) {
            chosenVoiceId = voiceId
            attempts.onSettingsChanged()
        }

        /**
         * `AndroidTtsController.startPlayback`: the sentence list is handed over again and
         * playback starts at [index], or at the first visible sentence when it is null.
         */
        private suspend fun freshStart(
            attempt: TtsPlaybackAttempt,
            index: Int?,
        ): TtsPlaybackFailureReason? {
            attempts.armStartupTimeout(attempt)
            engine.setPlaybackOperationCorrelationId(attempt.correlationId)
            engine.setSentences(sentences)
            engine.playFrom(
                index = index ?: FIRST_VISIBLE_SENTENCE_INDEX,
                voiceId = chosenVoiceId,
                rate = chosenRate,
                pitch = chosenPitch,
                completeChapterOnEnd = true,
                showPlaybackNotification = false,
            )
            return null
        }

        suspend fun restartAtIndex(index: Int) {
            engine.playFrom(
                index = index,
                voiceId = chosenVoiceId,
                rate = chosenRate,
                pitch = chosenPitch,
                completeChapterOnEnd = true,
                showPlaybackNotification = false,
            )
        }
    }

    /** Narration played from the top of the chapter to sentence 3, then paused there. */
    private fun TestScope.pausedOnSentenceThree(): Session {
        val session = startedSession(fromIndex = 0)
        repeat(3) {
            player.finishCurrentItem()
            runCurrent()
        }
        assertEquals(3, session.engine.currentSentenceIndex, "did not reach sentence 3")

        session.togglePlayback()
        runCurrent()
        assertEquals(false, session.engine.isPlaying.value, "did not pause")
        session.clearRecorded()
        return session
    }

    /** A session playing [fromIndex], with the player reporting what a real one reports. */
    private fun TestScope.startedSession(fromIndex: Int): Session {
        val session = newSession()
        session.userStart(fromIndex)
        runCurrent()
        startedPlaying()
        runCurrent()
        return session
    }

    private fun TestScope.newSession(): Session {
        val created = TtsReadAloudEngine(
            synthesizer = FakeTtsSynthesizer(),
            audioGenerator = audioSource,
            mediaPlaybackController = MediaPlaybackController(),
            playerProvider = playerProvider,
            mainContext = engineContext(),
        )
        engine = created
        val sessionScope = CoroutineScope(SupervisorJob() + engineContext())
        scopes += sessionScope
        val session = Session(created, sessionScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.attempts.operations.collect { operation ->
                session.outcomes += operation.describe()
                session.operations += operation
            }
        }
        return session
    }

    private fun TestScope.engineContext(): CoroutineContext =
        StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> uncaught += error }

    /** What the player reports once a playlist has been handed to it. */
    private fun startedPlaying() {
        player.reportPlaylistItem()
        player.reportReady()
        player.reportPlaying(true)
    }

    private fun TtsPlaybackOperation.describe(): String {
        val outcome = when (this) {
            is TtsPlaybackOperation.Attempted -> "attempted"
            is TtsPlaybackOperation.Succeeded -> "succeeded"
            is TtsPlaybackOperation.Failed -> "failed:${reasonCode.analyticsValue}"
            is TtsPlaybackOperation.Cancelled -> "cancelled:${reasonCode.analyticsValue}"
        }
        return "$outcome:${action.analyticsValue}"
    }

    private companion object {
        const val VOICE_ID = "en-us"
        const val OTHER_VOICE_ID = "en-gb"
        const val START_TIMEOUT_MS = 30_000L

        /** What `resolveStartIndex` returns with no visible sentence: the top of the page. */
        const val FIRST_VISIBLE_SENTENCE_INDEX = 0
    }
}
