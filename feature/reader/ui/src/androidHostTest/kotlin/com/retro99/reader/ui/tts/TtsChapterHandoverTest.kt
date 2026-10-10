package com.retro99.reader.ui.tts

import com.retro99.reader.ui.playback.MediaPlaybackController
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/**
 * Crossing a chapter boundary must not take the playback service down with it (TTS-F30).
 *
 * On the Samsung, with the screen off, the end of chapter 6 stopped the service; the next
 * chapter started it again, but a service started from the background cannot be promoted
 * — `startForegroundService() not allowed due to mAllowStartForeground false`, logged
 * twenty-odd times — so it stayed an ordinary background service and the system stopped
 * it 58 s later, eight sentences into the new chapter. The handover therefore has to keep
 * the service's player, and its audio, so the notification and the foreground promotion
 * both survive into the next chapter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsChapterHandoverTest {
    private val directory = Files.createTempDirectory("tts-chapter-handover").toFile()
    private val player = FakeTtsEnginePlayer().apply { reportsPlaybackItself = true }
    private val source = FakeSentenceAudioSource(directory)
    private val sentences = (0..1).map { TtsSentence(it, "p$it", "Sentence $it.") }
    private lateinit var engine: TtsReadAloudEngine

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @AfterTest fun tearDown() {
        if (::engine.isInitialized) engine.close()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    /** Reads both sentences of the chapter on the service's player, to its very end. */
    private suspend fun TestScope.readToChapterEnd(completeChapterOnEnd: Boolean) {
        engine = TtsReadAloudEngine(
            FakeTtsSynthesizer(), source, MediaPlaybackController(),
            FakeTtsEnginePlayerProvider(player), StandardTestDispatcher(testScheduler),
        )
        engine.setSentences(sentences)
        engine.playFrom(
            index = 0, voiceId = "en-us", rate = 1f, pitch = 1f,
            completeChapterOnEnd = completeChapterOnEnd, showPlaybackNotification = true,
        )
        runCurrent()
        assertTrue(engine.isPlaying.value, "setup did not reach audible playback")
        player.commands.clear()
        // Sentence 0 plays out, then sentence 1, then the queue is done.
        player.finishCurrentItem()
        runCurrent()
        assertEquals(1, engine.currentSentenceIndex)
        player.reportEnded()
        runCurrent()
    }

    @Test
    fun `a chapter handed over for auto advance keeps the service player and its audio`() = runTest {
        var completions = 0
        readToChapterEnd(completeChapterOnEnd = true)
        backgroundScope.launch { engine.chapterCompleted.collect { completions++ } }
        runCurrent()

        assertFalse(
            player.commands.any { it == "stop" || it == "clearItems" },
            "the handover emptied the service player: ${player.commands}",
        )
        assertTrue(player.itemCount > 0, "the service player was left with no audio at all")
        assertEquals(1, player.listenerCount, "the engine let go of the service player")
        // The session itself is over until the next chapter starts, so the screen is honest.
        assertFalse(engine.isSessionRunning.value)
        assertFalse(engine.isPlaying.value)
        assertEquals(-1, engine.currentSentenceIndex)
    }

    @Test
    fun `a chapter that ends with no auto advance lets the service player go`() = runTest {
        readToChapterEnd(completeChapterOnEnd = false)

        assertEquals(
            0, player.listenerCount,
            "nothing is coming next, so the engine should have let the player go",
        )
        assertFalse(engine.isSessionRunning.value)
    }

    /**
     * The handover is not one step. Completing the chapter moves the reader to the next
     * one, and that locator move reaches the controller before the next chapter starts;
     * the controller answers it with `engine.stop()`. On the phone that stop was what
     * actually took the service down — `state=IDLE` at 21:29:01.329, `onDestroy` 19 ms
     * later, then a background service start the system refused.
     */
    @Test
    fun `the locator stop that follows a handover keeps the service player`() = runTest {
        readToChapterEnd(completeChapterOnEnd = true)

        engine.stop()
        runCurrent()

        assertEquals(
            1, player.listenerCount,
            "the stop that follows the handover took the service player away",
        )
        assertFalse(
            player.commands.any { it == "stop" || it == "clearItems" },
            "the stop that follows the handover emptied the player: ${player.commands}",
        )
    }

    @Test
    fun `stop listening during a session still lets the service player go`() = runTest {
        engine = TtsReadAloudEngine(
            FakeTtsSynthesizer(), source, MediaPlaybackController(),
            FakeTtsEnginePlayerProvider(player), StandardTestDispatcher(testScheduler),
        )
        engine.setSentences(sentences)
        engine.playFrom(0, "en-us", 1f, 1f, showPlaybackNotification = true)
        runCurrent()
        assertTrue(engine.isPlaying.value)

        engine.stop()
        runCurrent()

        assertEquals(0, player.listenerCount, "Stop listening kept the service player")
    }

    @Test
    fun `the next chapter reuses the player it was handed`() = runTest {
        readToChapterEnd(completeChapterOnEnd = true)

        val nextChapter = (0..1).map { TtsSentence(it, "q$it", "Next $it.") }
        engine.setSentences(nextChapter)
        engine.playFrom(
            index = 0, voiceId = "en-us", rate = 1f, pitch = 1f,
            showPlaybackNotification = true,
        )
        runCurrent()

        assertTrue(engine.isPlaying.value, "the next chapter did not start")
        assertEquals(0, engine.currentSentenceIndex)
        assertEquals(1, player.listenerCount, "the next chapter attached a second listener")
    }
}
