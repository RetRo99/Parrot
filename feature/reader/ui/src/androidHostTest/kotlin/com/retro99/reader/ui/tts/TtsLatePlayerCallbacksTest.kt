package com.retro99.reader.ui.tts

import com.retro99.reader.ui.playback.MediaPlaybackController
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

/** Deliver callbacks captured before stop, including after the listener was removed. */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsLatePlayerCallbacksTest {
    private val directory = Files.createTempDirectory("tts-late").toFile()
    private val fake = FakeTtsEnginePlayer()
    private lateinit var listener: TtsEnginePlayerListener
    private var staleMediaId: String? = null
    private val player = object : TtsEnginePlayer by fake {
        override val currentMediaId: String? get() = staleMediaId ?: fake.currentMediaId
        override fun addListener(listener: TtsEnginePlayerListener) {
            this@TtsLatePlayerCallbacksTest.listener = listener
            fake.addListener(listener)
        }
    }
    private var engine: TtsReadAloudEngine? = null
    private val events = mutableListOf<String>()
    private val sentences = (0..2).map { TtsSentence(it, "p$it", "Sentence $it.") }

    @AfterTest
    fun tearDown() {
        engine?.close()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    @Test
    fun `ended after stop starts nothing and emits nothing`() = runTest {
        val engine = start()
        engine.stop()
        assertIgnored(engine) { listener.onEnded() }
    }

    @Test
    fun `ended after a failure stop starts nothing and emits nothing`() = runTest {
        val engine = start()
        listener.onError(IllegalStateException("decoder failed"))
        runCurrent()
        assertEquals(listOf("failure"), events)
        events.clear()
        assertIgnored(engine) { listener.onEnded() }
    }

    @Test
    fun `ended after pause does not restart or emit anything`() = runTest {
        val engine = start()
        engine.pause()
        fake.reportPlaying(false)
        val commands = fake.commands.toList()
        listener.onEnded()
        runCurrent()
        assertEquals(0, engine.currentSentenceIndex)
        assertEquals(commands, fake.commands)
        assertEquals(emptyList(), events)
        assertFalse(engine.isSessionRunning.value)
    }

    @Test
    fun `ended during a running session advances exactly once`() = runTest {
        val engine = start()
        val plays = fake.commands.count { it == "play" }
        listener.onEnded()
        runCurrent()
        assertEquals(1, engine.currentSentenceIndex)
        assertEquals(plays + 1, fake.commands.count { it == "play" })
        assertEquals(listOf("finished:0"), events)
    }

    @Test
    fun `ready after stop cannot restore a stale sentence`() = runTest {
        val engine = start()
        staleMediaId = fake.currentMediaId
        engine.stop()
        assertIgnored(engine) { listener.onReady() }
    }

    @Test
    fun `item transition after stop cannot restore a stale sentence`() = runTest {
        val engine = start()
        val mediaId = fake.currentMediaId
        engine.stop()
        assertIgnored(engine) { listener.onItemTransition(mediaId, true) }
    }

    @Test
    fun `error after stop emits no unsolicited failure`() = runTest {
        val engine = start()
        engine.stop()
        assertIgnored(engine) { listener.onError(IllegalStateException("late error")) }
    }

    @Test
    fun `playing callback after stop cannot revive the session`() = runTest {
        val engine = start()
        engine.stop()
        assertIgnored(engine) { listener.onIsPlayingChanged(true) }
    }

    @Test
    fun `auto transition after pause does not advance or emit anything`() = runTest {
        val engine = start()
        engine.pause()
        fake.reportPlaying(false)
        val commands = fake.commands.toList()
        listener.onItemTransition("tts::1", true)
        runCurrent()
        assertEquals(0, engine.currentSentenceIndex)
        assertEquals(commands, fake.commands)
        assertEquals(emptyList(), events)
    }

    private fun TestScope.assertIgnored(engine: TtsReadAloudEngine, callback: () -> Unit) {
        val commands = fake.commands.toList()
        callback()
        runCurrent()
        assertEquals(-1, engine.currentSentenceIndex)
        assertEquals(commands, fake.commands)
        assertEquals(emptyList(), events)
        assertFalse(engine.isSessionRunning.value)
        assertFalse(engine.isPlaying.value)
    }

    private suspend fun TestScope.start(): TtsReadAloudEngine {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = TtsReadAloudEngine(
            FakeTtsSynthesizer(), FakeSentenceAudioSource(directory),
            MediaPlaybackController(),
            object : TtsEnginePlayerProvider {
                override suspend fun notificationPlayer() = player
                override fun createLocalPlayer() = player
            },
            StandardTestDispatcher(testScheduler),
        ).also { this@TtsLatePlayerCallbacksTest.engine = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.finishedSentences.collect { events += "finished:${it.index}" }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.chapterCompleted.collect { events += "chapter" }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.playbackFailures.collect { events += "failure" }
        }
        engine.setSentences(sentences)
        engine.playFrom(0, "en-us", 1f, 1f, showPlaybackNotification = false)
        fake.reportPlaylistItem()
        fake.reportReady()
        fake.reportPlaying(true)
        runCurrent()
        return engine
    }
}
