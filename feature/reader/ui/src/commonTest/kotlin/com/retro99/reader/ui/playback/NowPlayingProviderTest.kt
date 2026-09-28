package com.retro99.reader.ui.playback

import com.retro99.books.domain.model.BookType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NowPlayingProviderTest {
    @Test
    fun stopForServerStopsOnlyMatchingMediaSession() {
        val provider = FakeNowPlayingProvider(activeServerId = "server-a")
        var stopStartedCount = 0

        val stopped = provider.stopForServer("server-a") {
            stopStartedCount += 1
        }

        assertTrue(stopped)
        assertEquals(1, stopStartedCount)
        assertEquals(1, provider.stopCount)
    }

    @Test
    fun stopForServerLeavesOtherServerPlaybackUntouched() {
        val provider = FakeNowPlayingProvider(activeServerId = "server-a")
        var stopStartedCount = 0

        val stopped = provider.stopForServer("server-b") {
            stopStartedCount += 1
        }

        assertFalse(stopped)
        assertEquals(0, stopStartedCount)
        assertEquals(0, provider.stopCount)
    }

    @Test
    fun stopForServerDoesNothingWhenThereIsNoMediaSession() {
        val provider = FakeNowPlayingProvider(activeServerId = null)

        val stopped = provider.stopForServer("server-a")

        assertFalse(stopped)
        assertEquals(0, provider.stopCount)
    }

    private class FakeNowPlayingProvider(activeServerId: String?) : NowPlayingProvider {
        override val nowPlayingInfo = MutableStateFlow(
            activeServerId?.let { serverId ->
                NowPlayingInfo(
                    serverId = serverId,
                    bookUuid = "book-id",
                    bookType = BookType.READALOUD,
                    bookTitle = "Test book",
                    coverUrl = null,
                )
            },
        )
        override val isPlaying = MutableStateFlow(activeServerId != null)
        var stopCount = 0
            private set

        override fun togglePlayPause() = Unit

        override fun stop() {
            stopCount += 1
        }
    }
}
