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

        var voiceId: String? = VOICE_ID
        var rate: Float = 1f
        var pitch: Float = 1f

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
                attempts.armStartupTimeout(attempt)
                engine.setPlaybackOperationCorrelationId(attempt.correlationId)
                engine.setSentences(sentences)
                engine.playFrom(
                    index = index,
                    voiceId = voiceId,
                    rate = rate,
                    pitch = pitch,
                    completeChapterOnEnd = true,
                    showPlaybackNotification = false,
                )
                null
            }
        }

        suspend fun restartAtIndex(index: Int) {
            engine.playFrom(
                index = index,
                voiceId = voiceId,
                rate = rate,
                pitch = pitch,
                completeChapterOnEnd = true,
                showPlaybackNotification = false,
            )
        }
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
        const val START_TIMEOUT_MS = 30_000L
    }
}
