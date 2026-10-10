package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.tts.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/**
 * The player is taken away from under the engine — the media service is destroyed, the
 * player released, the controller disconnected — and the screen must not lie about it
 * (TTS-F32). On the Samsung the system stopped the playback service about a minute after
 * the screen went off; the engine kept `isSessionRunning` true, so the bar still read
 * "Pause" and pressing it called `pause()` on a released ExoPlayer, which logged
 * "sending message to a Handler on a dead thread" and did nothing at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsPlayerGoneTest {
    private val directory = Files.createTempDirectory("tts-player-gone").toFile()
    private val player = FakeTtsEnginePlayer().apply { reportsPlaybackItself = true }
    private val source = FakeSentenceAudioSource(directory)
    private val sentences = (0..3).map { TtsSentence(it, "p$it", "Sentence $it.") }
    private lateinit var engine: TtsReadAloudEngine
    private lateinit var attempts: TtsPlaybackAttempts
    private lateinit var provider: FakeTtsEnginePlayerProvider
    private val operations = mutableListOf<String>()

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @AfterTest fun tearDown() {
        if (::engine.isInitialized) engine.close()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    /** Playing sentence 1, so "the same sentence" is not also the first one. */
    private suspend fun TestScope.start() {
        provider = FakeTtsEnginePlayerProvider(player)
        engine = TtsReadAloudEngine(
            FakeTtsSynthesizer(), source, MediaPlaybackController(),
            provider, StandardTestDispatcher(testScheduler),
        )
        attempts = TtsPlaybackAttempts(
            scope = backgroundScope, startupTimeoutMs = 30_000, engine = engine,
            nowMs = { 0L },
            restartAtIndex = { index ->
                engine.playFrom(index, "en-us", 1f, 1f, showPlaybackNotification = false)
            },
        )
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
        engine.setSentences(sentences)
        engine.playFrom(1, "en-us", 1f, 1f, showPlaybackNotification = false)
        runCurrent()
        assertTrue(engine.isPlaying.value, "setup did not reach audible playback")
        assertEquals(1, engine.currentSentenceIndex)
        operations.clear()
    }

    /** The reader's play/pause button, as `AndroidTtsController.togglePlayback` does it. */
    private fun toggle() {
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
            freshStart = { error("unexpected fresh start: the sentence should be resumed") },
        )
    }

    @Test
    fun `a player taken away leaves no running session and nothing audible`() = runTest {
        start()
        player.reportPlayerGone()
        runCurrent()

        assertFalse(engine.isPlaying.value, "isPlaying still true after the player went away")
        assertFalse(
            engine.isSessionRunning.value,
            "isSessionRunning still true after the player went away, so the button lies",
        )
        assertEquals(1, engine.currentSentenceIndex, "the sentence it was on was thrown away")
    }

    @Test
    fun `one press restarts the same sentence with one operation pair`() = runTest {
        start()
        player.reportPlayerGone()
        runCurrent()
        // The service is started again and hands out a new player, as production does.
        val replacement = FakeTtsEnginePlayer().apply { reportsPlaybackItself = true }
        provider.player = replacement

        toggle()
        runCurrent()

        assertEquals(listOf("attempted:resume", "succeeded:resume"), operations)
        assertEquals(1, engine.currentSentenceIndex, "a press moved off the paused sentence")
        assertTrue(engine.isPlaying.value, "one press did not get audio back")
        assertEquals(
            sentences[1].text,
            replacement.items.firstOrNull()?.let { item ->
                sentences[item.mediaId.substringAfterLast(':').toInt()].text
            },
            "the new player was not given the sentence it was on",
        )
        assertFalse(
            player.commands.any { it.endsWith("-on-gone-player") },
            "the engine still talked to the player that went away: ${player.commands}",
        )
    }

    @Test
    fun `a late callback from the player that went away starts nothing`() = runTest {
        start()
        player.reportPlayerGone()
        runCurrent()
        val synthesisedBefore = source.requestedTexts.size

        // Callbacks already posted to the looper before the service died.
        player.reportEnded()
        player.reportPlaying(true)
        player.reportReady()
        runCurrent()

        assertFalse(engine.isPlaying.value, "a late callback made the engine audible again")
        assertFalse(engine.isSessionRunning.value, "a late callback revived the session")
        assertEquals(1, engine.currentSentenceIndex, "a late callback advanced the sentence")
        assertEquals(
            synthesisedBefore, source.requestedTexts.size,
            "a late callback started synthesising: ${source.requestedTexts}",
        )
        assertEquals(emptyList(), operations)
    }
}
