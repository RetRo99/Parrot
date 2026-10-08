package com.retro99.statistics.domain

import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.books.domain.model.BookType
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookSessionTrackerTest {
    @Test
    fun interruptionAtGraceBoundaryMergesButOneMillisecondLaterSplits() {
        for (pause in listOf(10_000L, 10_001L)) {
            now = 0L
            saved.clear()
            val tracker = tracker()
            tracker.setBook("a", "A")
            tracker.setPlaying(true)
            now = 1_000L
            tracker.setPlaying(false)
            now += pause
            tracker.setPlaying(true)
            now += 2_000L
            tracker.finish()
            assertEquals(if (pause == 10_000L) listOf(3_000L) else listOf(1_000L, 2_000L), saved.map { it.durationMs })
        }
    }

    @Test
    fun bookChangeDuringPauseDoesNotCarryDurationOrMetadataToTheNextBook() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 1_000L
        tracker.setPlaying(false)
        now = 5_000L
        tracker.setBook("b", "B")
        tracker.setPlaying(true)
        now = 7_000L
        tracker.finish()
        assertEquals(listOf("a", "b"), saved.map { it.bookUuid })
        assertEquals(listOf("A", "B"), saved.map { it.bookTitle })
        assertEquals(listOf(1_000L, 2_000L), saved.map { it.durationMs })
        assertEquals(listOf(1_000L, 7_000L), saved.map { it.endTime })
    }

    @Test
    fun teardownWhileInterruptedExcludesThePauseAndLateStopCallbacksDoNotDuplicateIt() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 3_000L
        tracker.setPlaying(false)
        now = 100_000L
        repeat(4) { tracker.finish(); tracker.setPlaying(false) }
        assertEquals(1, saved.size)
        assertEquals(3_000L, saved.single().durationMs)
        assertEquals(3_000L, saved.single().endTime)
    }
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

    @Test
    fun repeatedPauseAndDestroyNotificationsSaveOnlyOnce() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 2_000L
        repeat(3) { tracker.setPlaying(false) }
        repeat(3) { tracker.finish() }
        assertEquals(1, saved.size)
        assertEquals(2_000L, saved.single().durationMs)
    }

    @Test
    fun zeroLengthPlaybackDoesNotCreateAnEmptySession() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        tracker.setPlaying(false)
        assertEquals(emptyList(), saved)
        tracker.setPlaying(true)
        now = 1_000L
        tracker.finish()
        assertEquals(1_000L, saved.single().durationMs)
    }

    @Test
    fun metadataRefreshKeepsTheStartAndUsesTheUpdatedTitle() {
        val tracker = tracker()
        now = 100L
        tracker.setBook("a", "")
        tracker.setPlaying(true)
        now = 600L
        tracker.setBook("a", "Updated title")
        tracker.setPlaying(true)
        now = 1_100L
        tracker.finish()
        val session = saved.single()
        assertEquals("a", session.bookUuid)
        assertEquals("Updated title", session.bookTitle)
        assertEquals(BookType.AUDIOBOOK, session.bookType)
        assertEquals(100L, session.startTime)
        assertEquals(1_100L, session.endTime)
        assertEquals(1_000L, session.durationMs)
        assertEquals(0, session.readingSpeedWpm)
    }

    @Test
    fun wallClockChangesDoNotChangeListeningDuration() {
        var elapsed = 0L
        val tracker = AudiobookSessionTracker(
            saveSession = saved::add,
            nowMillis = { now },
            createTimer = { ActiveSessionTimer { elapsed } },
        )
        now = 50_000L
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 10_000L // User changes the device clock.
        elapsed = 3_000L
        tracker.finish()
        assertEquals(3_000L, saved.single().durationMs)
        assertEquals(50_000L, saved.single().startTime)
        assertEquals(10_000L, saved.single().endTime)
    }

    @Test
    fun playbackBeforeBookIdentificationIsNotAttributedToTheNextBook() {
        val tracker = tracker()
        tracker.setPlaying(true)
        now = 5_000L
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 7_000L
        tracker.finish()
        assertEquals(5_000L, saved.single().startTime)
        assertEquals(2_000L, saved.single().durationMs)
    }

    @Test
    fun aShortPauseLikeASpeakWordInterruptionKeepsOneSession() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 2_000L
        tracker.setPlaying(false) // The dictionary speaks a word: pause and resume.
        now = 3_500L
        tracker.setPlaying(true)
        now = 8_000L
        tracker.setPlaying(false)
        tracker.finish()
        assertEquals(1, saved.size)
        val session = saved.single()
        assertEquals(0L, session.startTime)
        assertEquals(8_000L, session.endTime)
        // Active time only: the 1.5 s word pause is excluded, the pause gaps never counted.
        assertEquals(6_500L, session.durationMs)
    }

    @Test
    fun aPauseBeyondTheGraceStillSplitsSessions() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 2_000L
        tracker.setPlaying(false)
        now = 2_000L + AudiobookSessionTracker.DEFAULT_MERGE_PAUSE_MS + 1
        tracker.setPlaying(true)
        now += 4_000L
        tracker.finish()
        assertEquals(listOf(2_000L, 4_000L), saved.map { session -> session.durationMs })
    }

    @Test
    fun resumingAfterAShortPauseEndsTheOldSessionAtItsPauseTime() {
        val tracker = tracker()
        tracker.setBook("a", "A")
        tracker.setPlaying(true)
        now = 1_000L
        tracker.setPlaying(false)
        now = 2_000L
        tracker.setPlaying(true)
        now = 6_000L
        tracker.setPlaying(false)
        now = 60_000L // Service stops long after the last pause.
        tracker.finish()
        val session = saved.single()
        assertEquals(6_000L, session.endTime)
        assertEquals(5_000L, session.durationMs)
    }
}
