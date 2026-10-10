package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.tts.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TtsOutsidePauseTest {
    private val directory = Files.createTempDirectory("tts-outside-pause").toFile()
    private val player = FakeTtsEnginePlayer().apply { reportsPlaybackItself = true }
    private val source = FakeSentenceAudioSource(directory)
    private val sentences = (0..2).map { TtsSentence(it, "p$it", "Sentence $it.") }
    private lateinit var engine: TtsReadAloudEngine
    private lateinit var attempts: TtsPlaybackAttempts
    private val operations = mutableListOf<String>()
    private var rate = 1f

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() {
        if (::engine.isInitialized) engine.close()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private suspend fun TestScope.start(gap: Boolean = false) {
        engine = TtsReadAloudEngine(FakeTtsSynthesizer(), source, MediaPlaybackController(),
            FakeTtsEnginePlayerProvider(player), StandardTestDispatcher(testScheduler))
        attempts = TtsPlaybackAttempts(scope = backgroundScope, startupTimeoutMs = 30_000,
            engine = engine, nowMs = { 0L }, restartAtIndex = { index ->
                engine.playFrom(index, "en-us", rate, 1f, showPlaybackNotification = false)
            })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            attempts.operations.collect {
                val outcome = when (it) {
                    is TtsPlaybackOperation.Attempted -> "attempted"
                    is TtsPlaybackOperation.Succeeded -> "succeeded"
                    is TtsPlaybackOperation.Failed -> "failed"
                    is TtsPlaybackOperation.Cancelled -> "cancelled"
                }
                operations += "$outcome:${it.action.analyticsValue}"
            }
        }
        if (gap) source.deferredTexts += sentences.drop(1).map { it.text }
        engine.setSentences(sentences)
        engine.playFrom(0, "en-us", rate, 1f, showPlaybackNotification = false)
        runCurrent()
        assertTrue(engine.isPlaying.value)
        if (gap) {
            player.reportEnded()
            runCurrent()
            assertTrue(source.isAwaitingCompletion(sentences[1].text))
        }
    }

    private fun toggle() {
        if (engine.isSessionRunning.value) engine.pause()
        else attempts.onPlayPressed(resume = { attempt ->
            attempts.armStartupTimeout(attempt)
            engine.resume()
            null
        }, freshStart = { error("unexpected fresh start") })
    }

    @Test fun `outside pause is neither running nor audible`() = runTest {
        start()
        player.reportOutsidePause()
        assertFalse(engine.isPlaying.value)
        assertFalse(engine.isSessionRunning.value, "outside pause left session running")
    }

    @Test fun `one toggle resumes same sentence with one operation pair`() = runTest {
        start()
        player.reportOutsidePause()
        toggle()
        runCurrent()
        assertEquals(listOf("attempted:resume", "succeeded:resume"), operations)
        assertTrue(engine.isPlaying.value)
        assertEquals(0, engine.currentSentenceIndex)
    }

    @Test fun `direct resume continues same sentence`() = runTest {
        start()
        player.reportOutsidePause()
        engine.resume()
        runCurrent()
        assertTrue(engine.isPlaying.value)
        assertEquals(0, engine.currentSentenceIndex)
    }

    @Test fun `outside paused settings wait for play on same sentence`() = runTest {
        start()
        player.reportOutsidePause()
        rate = 1.1f
        attempts.onSettingsChanged()
        runCurrent()
        assertEquals(emptyList(), operations, "settings change restarted outside-paused narration")
        assertFalse(engine.isPlaying.value)
        toggle()
        runCurrent()
        assertTrue(engine.isPlaying.value)
        assertEquals(0, engine.currentSentenceIndex)
        assertEquals(1.1f, source.requests.last { it.text == sentences[0].text }.rate)
    }

    @Test fun `outside resume restores running and normal advancement`() = runTest {
        start()
        player.reportOutsidePause()
        player.reportOutsideResume()
        assertTrue(engine.isSessionRunning.value)
        player.finishCurrentItem()
        runCurrent()
        assertEquals(1, engine.currentSentenceIndex)
    }

    @Test fun `late ended after outside pause starts nothing`() = runTest {
        start()
        player.reportOutsidePause()
        val commands = player.commands.toList()
        player.reportEnded()
        runCurrent()
        assertEquals(commands, player.commands, "late ended started outside-paused narration")
        assertEquals(0, engine.currentSentenceIndex)
    }

    @Test fun `late ready after outside pause starts nothing`() = runTest {
        start()
        player.reportOutsidePause()
        val commands = player.commands.toList()
        player.reportReady()
        runCurrent()
        assertEquals(commands, player.commands)
        assertFalse(engine.isPlaying.value)
    }

    @Test fun `buffering preserves running and callback ownership`() = runTest {
        start()
        player.reportPlaying(false)
        assertTrue(engine.isSessionRunning.value)
        player.reportedDurationMs = 2_000
        player.reportReady()
        assertEquals(2_000, engine.currentSentenceDurationMs.value)
        player.reportPlaying(true)
        assertTrue(engine.isPlaying.value)
    }

    @Test fun `outside pause in synthesis gap keeps arriving audio silent`() = runTest {
        start(gap = true)
        player.reportOutsidePause()
        source.completeSynthesis(sentences[1].text)
        runCurrent()
        assertFalse(engine.isPlaying.value, "gap synthesis played after outside pause")
        toggle()
        runCurrent()
        assertTrue(engine.isPlaying.value)
        assertEquals(1, engine.currentSentenceIndex)
    }

    @Test fun `word and preview running decision does not resume outside pause`() = runTest {
        start()
        player.reportOutsidePause()
        // Both controller interruption paths capture this decision before their audio.
        val resumeAfterInterruption = engine.isSessionRunning.value
        if (resumeAfterInterruption) engine.pause()
        if (resumeAfterInterruption) engine.resume()
        runCurrent()
        assertFalse(engine.isPlaying.value, "interruption resumed outside-paused narration")
    }
}
