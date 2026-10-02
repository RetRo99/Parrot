package com.retro99.statistics.domain

import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookSessionTrackerTest {
    private var now = 0L
    private val saved = mutableListOf<ReadingSessionDomainModel>()
    private fun tracker() = AudiobookSessionTracker(
        saveSession = saved::add,
        nowMillis = { now },
        createTimer = { ActiveSessionTimer { now } },
    )

    @Test
    fun listeningContinuesUntilPlaybackStopsNotWhenTheScreenCloses() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 1_000L
        tracker.setBook("a", "A") // Reconnection/metadata refresh.
        tracker.setPlaying(true)
        now = 5_000L
        tracker.setPlaying(false)
        tracker.finish()
        assertEquals(listOf(5_000L), saved.map { it.durationMs })
        now = 100_000L // Paused time is excluded.
        tracker.setPlaying(true)
        now = 102_000L
        tracker.finish() // Service destruction.
        assertEquals(listOf(5_000L, 2_000L), saved.map { it.durationMs })
    }

    @Test
    fun switchingBooksFlushesTheOldBookAndDoesNotCountNonAudiobooks() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 1_000L
        tracker.setBook("b", "B")
        tracker.setPlaying(true)
        now = 3_000L
        tracker.setBook(null, "Read aloud")
        tracker.setPlaying(true)
        now = 10_000L
        tracker.finish()
        assertEquals(listOf("a", "b"), saved.map { it.bookUuid })
        assertEquals(listOf(1_000L, 2_000L), saved.map { it.durationMs })
    }

    @Test
    fun openingWithoutPlayingDoesNotCreateASession() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        now = 100_000L
        tracker.finish()
        assertEquals(emptyList(), saved)
    }
}
