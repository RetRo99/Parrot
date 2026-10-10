package com.retro99.reader.ui.tts

import com.retro99.reader.ui.playback.MediaPlaybackController
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class TtsRunningCallbackOwnershipTest {
    @Test
    fun `buffering does not discard the running sessions player callbacks`() = runTest {
        val directory = Files.createTempDirectory("tts-running-callback").toFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val player = FakeTtsEnginePlayer()
        val engine = TtsReadAloudEngine(
            FakeTtsSynthesizer(), FakeSentenceAudioSource(directory),
            MediaPlaybackController(), FakeTtsEnginePlayerProvider(player), dispatcher,
        )
        try {
            engine.setSentences((0..1).map { TtsSentence(it, "p$it", "Sentence $it.") })
            engine.playFrom(0, "en-us", 1f, 1f, showPlaybackNotification = false)
            player.reportPlaylistItem()
            player.reportReady()
            player.reportPlaying(true)
            runCurrent()

            // A decoder can buffer without the queue ending or anyone asking for silence.
            player.reportPlaying(false)
            player.reportReady()
            player.reportPlaying(true)
            player.reportEnded()
            runCurrent()

            assertEquals(1, engine.currentSentenceIndex)
            assertTrue(engine.isSessionRunning.value)
        } finally {
            engine.close()
            Dispatchers.resetMain()
            directory.deleteRecursively()
        }
    }
}
