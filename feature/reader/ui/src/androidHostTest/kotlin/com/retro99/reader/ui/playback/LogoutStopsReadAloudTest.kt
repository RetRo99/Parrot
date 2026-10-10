package com.retro99.reader.ui.playback

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.tts.FakeSentenceAudioSource
import com.retro99.reader.ui.tts.FakeTtsEnginePlayer
import com.retro99.reader.ui.tts.FakeTtsEnginePlayerProvider
import com.retro99.reader.ui.tts.FakeTtsSynthesizer
import com.retro99.reader.ui.tts.TtsPlaybackInfo
import com.retro99.reader.ui.tts.TtsReadAloudEngine
import com.retro99.reader.ui.tts.TtsSentence
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * QA-BUG-0049: logging out of a server stops the audio that belongs to that server and
 * leaves other audio alone. `stopForServer` decides that from `nowPlayingInfo.serverId`,
 * so this covers what read-aloud puts there and whether the stop reaches the engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutStopsReadAloudTest {

    private val audioDirectory: File = Files.createTempDirectory("tts-logout").toFile()
    private val player = FakeTtsEnginePlayer()
    private val playerProvider = FakeTtsEnginePlayerProvider(player)
    private val audioSource = FakeSentenceAudioSource(audioDirectory)
    // Built lazily: it opens a main-dispatcher scope, so it must wait for setMain().
    private val mediaPlaybackController by lazy { MediaPlaybackController() }

    private val uncaught = mutableListOf<Throwable>()
    private var engine: TtsReadAloudEngine? = null

    private val sentences = (0..2).map { index ->
        TtsSentence(index = index, elementId = "p$index", text = "Sentence $index.")
    }

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
    fun `read-aloud of a server book reports that server as now playing`() = runTest {
        val engine = createEngine()
        startReadAloud(engine, serverId = "storyteller")

        val provider = provider()
        assertEquals("storyteller", provider.nowPlayingInfo.value?.serverId)
    }

    @Test
    fun `read-aloud of a local book reports the local library, not a server`() = runTest {
        val engine = createEngine()
        startReadAloud(engine, serverId = LOCAL_SERVER_ID)

        val provider = provider()
        assertEquals(LOCAL_SERVER_ID, provider.nowPlayingInfo.value?.serverId)
        // Signing out of a remote server leaves a local book reading.
        assertFalse(provider.stopForServer("storyteller"))
        assertTrue(engine.isSessionRunning.value)
    }

    /**
     * Read-aloud without the media notification: it never starts there, because
     * `AndroidTtsController.startLoadedPlayback` refuses without the notification
     * permission and always asks for `showPlaybackNotification = true`. Nothing is
     * reported as now playing, so nothing can be stopped by server either.
     */
    @Test
    fun `read-aloud on its own player reports nothing as now playing`() = runTest {
        val engine = createEngine()
        startReadAloud(engine, serverId = "storyteller", showPlaybackNotification = false)

        val provider = provider()
        assertNull(provider.nowPlayingInfo.value)
    }

    @Test
    fun `logging out of the server a book is read aloud from stops the engine`() = runTest {
        val engine = createEngine()
        startReadAloud(engine, serverId = "storyteller")

        val provider = provider()
        assertTrue(provider.stopForServer("storyteller"))
        runCurrent()

        assertFalse(engine.isSessionRunning.value, "Read-aloud keeps running after the logout")
        assertNull(provider.nowPlayingInfo.value)
    }

    @Test
    fun `logging out of another server leaves read-aloud alone`() = runTest {
        val engine = createEngine()
        startReadAloud(engine, serverId = "storyteller")

        val provider = provider()
        assertFalse(provider.stopForServer("audiobookshelf"))
        runCurrent()

        assertTrue(engine.isSessionRunning.value)
    }

    // ---- fixture ----

    private fun provider(): NowPlayingProvider =
        AndroidNowPlayingProvider(
            mediaPlaybackController = mediaPlaybackController,
            readAloud = { engine?.stop() },
        )

    private fun TestScope.createEngine(): TtsReadAloudEngine =
        TtsReadAloudEngine(
            synthesizer = FakeTtsSynthesizer(),
            audioGenerator = audioSource,
            mediaPlaybackController = mediaPlaybackController,
            playerProvider = playerProvider,
            mainContext = engineContext(),
        ).also { created -> engine = created }

    private fun TestScope.engineContext(): CoroutineContext =
        StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> uncaught += error }

    private fun TestScope.startReadAloud(
        engine: TtsReadAloudEngine,
        serverId: String,
        showPlaybackNotification: Boolean = true,
    ) {
        engine.setPlaybackInfo(
            TtsPlaybackInfo(
                serverId = serverId,
                bookUuid = "book-1",
                bookType = BookType.EBOOK,
                bookTitle = "A book",
                chapterTitle = "A chapter",
                coverArtwork = null,
            ),
        )
        engine.setSentences(sentences)
        launch {
            engine.playFrom(
                index = 0,
                voiceId = "system-en",
                rate = 1f,
                pitch = 1f,
                completeChapterOnEnd = true,
                showPlaybackNotification = showPlaybackNotification,
            )
        }
        runCurrent()
        player.reportPlaylistItem()
        runCurrent()
        assertTrue(uncaught.isEmpty(), "Starting read-aloud threw: $uncaught")
    }
}
