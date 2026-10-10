package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.tts.*
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TtsColdStartTest {
    private val directory = Files.createTempDirectory("tts-cold-start").toFile()
    private val player = FakeTtsEnginePlayer().apply { reportsPlaybackItself = true }
    private val model = ColdSynthesizer()
    private val operations = mutableListOf<TtsPlaybackOperation>()
    private lateinit var engine: TtsReadAloudEngine
    private lateinit var attempts: TtsPlaybackAttempts
    private var voice: String? = "kokoro:0"

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.close()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    @Test
    fun `forty second model load then five second sentence succeeds`() = runTest {
        setup()
        start()
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(listOf("attempted", "succeeded"), outcomes())
        assertEquals(45_000L, (operations.last() as TtsPlaybackOperation.Succeeded).durationMs)
        assertFalse(attempts.isStartPending.value)
    }

    @Test
    fun `a hanging model load fails once at sixty seconds`() = runTest {
        model.hangs = true
        setup()
        start()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(listOf("attempted"), outcomes())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("attempted", "failed"), outcomes())
        val failure = operations.last() as TtsPlaybackOperation.Failed
        assertEquals(TtsPlaybackFailureReason.START_TIMEOUT, failure.reasonCode)
        assertEquals(60_000L, failure.durationMs)
        assertFalse(attempts.isStartPending.value)
        assertFalse(model.loading)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, operations.size)
    }

    @Test
    fun `system voice start is armed immediately`() = runTest {
        voice = "en-us"
        model.sentenceHangs = true
        setup()
        start()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("attempted", "failed"), outcomes())
        assertEquals(TtsPlaybackFailureReason.START_TIMEOUT,
            (operations.last() as TtsPlaybackOperation.Failed).reasonCode)
        assertEquals(0, model.loads)
        assertFalse(attempts.isStartPending.value)
    }

    @Test
    fun `stop during model load cancels once with nothing pending`() = runTest {
        setup()
        start()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(model.loading)
        attempts.cancelActive()
        engine.stop()
        runCurrent()
        assertEquals(listOf("attempted", "cancelled"), outcomes())
        assertFalse(model.loading)
        assertFalse(attempts.isStartPending.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, operations.size)
        assertEquals(0, player.commands.count { it == "play" })
    }

    @Test
    fun `playback start stays pending throughout the model load`() = runTest {
        setup()
        start()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(model.loading)
        assertTrue(attempts.isStartPending.value)
        assertEquals(listOf("attempted"), outcomes())
        attempts.cancelActive()
        engine.stop()
    }

    @Test
    fun `voice change to a cold neural voice uses the load deadline`() = runTest {
        voice = "en-us"
        setup()
        start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("attempted", "succeeded"), outcomes())
        operations.clear()
        voice = "kokoro:0"
        attempts.onSettingsChanged()
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(listOf("attempted", "succeeded"), outcomes())
        assertTrue(operations.all { it.action == TtsPlaybackAction.SETTINGS_CHANGE })
        assertEquals(45_000L, (operations.last() as TtsPlaybackOperation.Succeeded).durationMs)
    }

    @Test
    fun `supertonic cold load gets the same separate deadline`() = runTest {
        voice = "supertonic:0"
        setup()
        start()
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(listOf("attempted", "succeeded"), outcomes())
    }

    @Test
    fun `resume with a cold neural model uses the load deadline`() = runTest {
        model.loadMs = 0
        setup()
        start()
        advanceTimeBy(5_000)
        runCurrent()
        engine.pause()
        model.loaded = false
        model.loadMs = 40_000
        operations.clear()
        attempts.onPlayPressed(
            resume = { attempts.prepareStart(it); engine.resume(); null },
            freshStart = { error("resume lost its sentence") },
        )
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(listOf("attempted", "succeeded"), outcomes())
        assertTrue(operations.all { it.action == TtsPlaybackAction.RESUME })
        assertEquals(40_000L, (operations.last() as TtsPlaybackOperation.Succeeded).durationMs)
        assertTrue(model.loaded)
    }

    private fun TestScope.setup() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        engine = TtsReadAloudEngine(
            model,
            object : TtsSentenceAudioSource {
                override suspend fun synthesize(text: String, voiceId: String?, rate: Float,
                    pitch: Float): TtsSynthesisResult =
                    model.synthesize(text, voiceId, rate, pitch, File(directory, "sentence.wav"))
            },
            MediaPlaybackController(), FakeTtsEnginePlayerProvider(player),
            StandardTestDispatcher(testScheduler),
        )
        engine.setSentences(listOf(TtsSentence(0, "p0", "First sentence.")))
        attempts = TtsPlaybackAttempts(
            scope = backgroundScope,
            startupTimeoutMs = 30_000,
            engine = engine,
            nowMs = { testScheduler.currentTime },
            synthesizer = model,
            selectedVoiceId = { voice },
            restartAtIndex = { play(it) },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            attempts.operations.collect { operations += it }
        }
        runCurrent()
    }

    private fun start() {
        attempts.request(TtsPlaybackAction.CONTROLS) {
            attempts.prepareStart(it)
            play(0)
            null
        }
    }

    private suspend fun play(index: Int) {
        engine.playFrom(index, voice, 1f, 1f, showPlaybackNotification = false)
    }

    private fun outcomes() = operations.map {
        when (it) {
            is TtsPlaybackOperation.Attempted -> "attempted"
            is TtsPlaybackOperation.Succeeded -> "succeeded"
            is TtsPlaybackOperation.Failed -> "failed"
            is TtsPlaybackOperation.Cancelled -> "cancelled"
        }
    }

    /** Like the real neural synthesizers, synthesis ensures a cold engine is loaded. */
    private inner class ColdSynthesizer : TtsSynthesizer by FakeTtsSynthesizer() {
        var loadMs = 40_000L
        var hangs = false
        var sentenceHangs = false
        var loaded = false
        var loading = false
        var loads = 0

        override suspend fun warmUp(voiceId: String?): Boolean {
            if (loaded) return true
            loads++
            loading = true
            try {
                if (hangs) awaitCancellation()
                delay(loadMs)
                loaded = true
                return true
            } finally {
                loading = false
            }
        }

        override suspend fun synthesize(text: String, voiceId: String?, rate: Float,
            pitch: Float, outputFile: File): TtsSynthesisResult {
            if (voiceId.neuralVoicePackage() != null) warmUp(voiceId)
            if (sentenceHangs) awaitCancellation()
            delay(5_000)
            outputFile.writeBytes(ByteArray(64))
            return TtsSynthesisResult(TtsSynthesisStatus.SUCCESS, outputFile, durationMs = 1_000)
        }
    }
}
