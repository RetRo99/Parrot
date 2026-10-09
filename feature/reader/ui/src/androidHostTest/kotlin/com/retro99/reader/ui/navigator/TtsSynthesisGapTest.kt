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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The gap a slow voice leaves between sentences (TTS-F26). Kokoro takes 2–9 s per sentence
 * on the test phone, so the player regularly runs out of audio before the next clip
 * exists. For that second or two no audio is audible although narration is running, and
 * every decision that asks "is it playing?" gets the wrong answer: the device run of
 * 2026-10-09 lost a speed change to the paused path, then stopped silently at sentence 145
 * of 151, then hit the 30 s start deadline on the next play press.
 *
 * Same shape as [TtsPlaybackAttemptsTest]: a real [TtsReadAloudEngine] behind the run 2a
 * player seam, driven by the attempt bookkeeping the controller delegates to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsSynthesisGapTest {

    private val audioDirectory: File = Files.createTempDirectory("tts-gap").toFile()
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

    // ---- a: the gap is a running session ----

    @Test
    fun `a session waiting for its next sentence still counts as running`() = runTest {
        val session = inTheGap()

        assertEquals(
            false,
            session.engine.isPlaying.value,
            "no audio is audible in the gap, so isPlaying must stay false",
        )
        assertTrue(
            session.engine.isSessionRunning.value,
            "the gap is not a paused session: nothing was paused, stopped or finished",
        )
    }

    // ---- b: a setting changed in the gap ----

    @Test
    fun `a speed change in the gap is a tracked settings change restart`() = runTest {
        val session = inTheGap()

        session.setRate(1.5f)
        runCurrent()
        audioSource.completeSynthesis(sentences[1].text)
        runCurrent()

        assertEquals(
            listOf("attempted:settings_change", "succeeded:settings_change"),
            session.outcomes,
        )
        val clipsForSentenceOne = player.queuedItems
            .filter { item -> item.mediaId.endsWith(":1") }
        assertTrue(
            clipsForSentenceOne.isNotEmpty() &&
                    clipsForSentenceOne.all { item -> item.file.name.contains("-1.5-") },
            "clips queued for sentence 1: ${clipsForSentenceOne.map { it.file.name }}",
        )
        assertEquals(1, session.engine.currentSentenceIndex)
        assertTrue(uncaught.isEmpty(), "escaped the scope: $uncaught")
    }

    // ---- c: pause pressed in the gap ----

    @Test
    fun `pause in the gap keeps the next sentence silent until play is pressed`() = runTest {
        val session = inTheGap()

        session.togglePlayback()
        runCurrent()
        assertEquals(
            false,
            session.engine.isSessionRunning.value,
            "the pause left the session running",
        )

        audioSource.completeSynthesis(sentences[1].text)
        runCurrent()
        assertEquals(
            false,
            session.engine.isPlaying.value,
            "sentence 1 started playing although the user had paused",
        )

        session.togglePlayback()
        runCurrent()
        assertTrue(session.engine.isPlaying.value, "play did not resume the paused sentence")
        assertEquals(1, session.engine.currentSentenceIndex)
        assertTrue(uncaught.isEmpty(), "escaped the scope: $uncaught")
    }

    // ---- d: the whole device sequence of 2026-10-09, step 4b ----

    @Test
    fun `a speed change in the gap still reads the chapter to its end`() = runTest {
        val session = inTheGap()

        session.setRate(1.5f)
        runCurrent()
        audioSource.completeSynthesis(sentences[1].text)
        runCurrent()
        // Every later clip arrives while the one before it is playing, and the player runs
        // out of audio just after the arrival: the phone's 20:36:13 ordering.
        for (index in 2..sentences.lastIndex) {
            audioSource.completeSynthesis(sentences[index].text)
            runCurrent()
            player.reportEnded()
            runCurrent()
        }
        // The last sentence plays out.
        player.reportEnded()
        runCurrent()

        assertEquals(
            sentences.indices.toList(),
            session.finished.map { sentence -> sentence.index },
            "sentences that played to the end; narration stopped silently if any are missing",
        )
        assertEquals(1, session.chapterCompletions, "chapter completions")
        val clipsAfterTheChange = player.queuedItems
            .filterNot { item -> item.mediaId.endsWith(":0") }
        assertTrue(
            clipsAfterTheChange.isNotEmpty() &&
                    clipsAfterTheChange.all { item -> item.file.name.contains("-1.5-") },
            "clips queued after the change: ${clipsAfterTheChange.map { it.file.name }}",
        )
        assertEquals(
            listOf("attempted:settings_change", "succeeded:settings_change"),
            session.outcomes,
        )
        assertTrue(uncaught.isEmpty(), "escaped the scope: $uncaught")
    }

    // ---- e: the play press after the device's silent stop ----

    @Test
    fun `a play press after the end of queue race resumes inside the start deadline`() = runTest {
        val session = inTheGap()

        session.setRate(1.5f)
        runCurrent()
        audioSource.completeSynthesis(sentences[1].text)
        runCurrent()
        // Sentence 2's clip arrives and plays on from sentence 1 the ordinary way.
        audioSource.completeSynthesis(sentences[2].text)
        runCurrent()
        player.finishCurrentItem()
        runCurrent()
        assertEquals(2, session.engine.currentSentenceIndex, "did not play on to sentence 2")
        session.clearRecorded()

        // Sentence 3's clip is appended in the window before the end-of-queue callback.
        audioSource.completeSynthesis(sentences[3].text)
        runCurrent()
        player.reportEnded()
        runCurrent()

        // What the user did on the phone: pressed the button, then pressed it again.
        session.togglePlayback()
        runCurrent()
        session.togglePlayback()
        runCurrent()
        advanceTimeBy(START_TIMEOUT_MS + 1)

        assertTrue(
            session.outcomes.none { outcome -> outcome.contains("start_timeout") },
            "a press after the gap sat until the start deadline: ${session.outcomes}",
        )
        assertTrue(
            session.engine.isSessionRunning.value,
            "narration is not running after the press: ${player.commands}",
        )
        assertEquals(3, session.engine.currentSentenceIndex)
        assertTrue(uncaught.isEmpty(), "escaped the scope: $uncaught")
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

        /** Sentences whose audio played to the end, and chapter ends. */
        val finished = mutableListOf<TtsSentence>()
        var chapterCompletions = 0

        fun clearRecorded() {
            outcomes.clear()
            operations.clear()
        }

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

        fun userStart(index: Int) {
            attempts.request(TtsPlaybackAction.CONTROLS) { attempt ->
                freshStart(attempt, index)
            }
        }

        /** `AndroidTtsController.togglePlayback`. */
        fun togglePlayback() {
            if (engine.isSessionRunning.value) {
                engine.pause()
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

    /**
     * A session sitting inside the gap: sentence 0's audio has ended in the player and
     * sentence 1 is still being synthesised. Every sentence after the first is deferred,
     * so the test decides when each clip arrives.
     */
    private fun TestScope.inTheGap(): Session {
        val session = newSession()
        player.reportsPlaybackItself = true
        audioSource.deferredTexts += sentences.drop(1).map { sentence -> sentence.text }

        session.userStart(0)
        runCurrent()
        assertTrue(session.engine.isPlaying.value, "sentence 0 did not start playing")

        player.finishCurrentItem()
        runCurrent()
        assertEquals(1, session.engine.currentSentenceIndex, "did not move on to sentence 1")
        assertTrue(
            audioSource.isAwaitingCompletion(sentences[1].text),
            "sentence 1 is not being synthesised: ${audioSource.requestedTexts}",
        )
        session.clearRecorded()
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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            created.finishedSentences.collect { sentence -> session.finished += sentence }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            created.chapterCompleted.collect { session.chapterCompletions++ }
        }
        return session
    }

    private fun TestScope.engineContext(): CoroutineContext =
        StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> uncaught += error }

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
        const val START_TIMEOUT_MS = 30_000L

        /** What `resolveStartIndex` returns with no visible sentence: the top of the page. */
        const val FIRST_VISIBLE_SENTENCE_INDEX = 0
    }
}
