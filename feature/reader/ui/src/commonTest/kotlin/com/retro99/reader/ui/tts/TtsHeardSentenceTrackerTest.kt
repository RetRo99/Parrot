package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtsHeardSentenceTrackerTest {

    private val tracker = TtsHeardSentenceTracker()

    @Test
    fun sentencesPlayedThroughAreFinished() {
        tracker.onPlaylistStarted(index = 10, progress = 0.0)

        assertEquals(10, tracker.onTransition(index = 11, auto = true))
        assertEquals(11, tracker.onEnded())
    }

    @Test
    fun aSeekTargetStillSynthesisingIsNotReportedForTheItemThatPlayedOut() {
        // Sentence 10 plays; the user seeks to 40, which isn't ready yet.
        tracker.onPlaylistStarted(index = 10, progress = 0.0)

        // The old playlist plays on to 11, then ends.
        assertEquals(10, tracker.onTransition(index = 11, auto = true))
        // What played out is reported, never 40.
        assertEquals(11, tracker.onEnded())
    }

    @Test
    fun aSentenceStartedPartWayIsNotFinished() {
        tracker.onPlaylistStarted(index = 7, progress = 1.0)

        assertNull(tracker.onEnded())
    }

    @Test
    fun seekingIntoASentenceMakesItUnheard() {
        tracker.onPlaylistStarted(index = 3, progress = 0.0)
        tracker.onSeek(positionMs = 2_400)

        assertNull(tracker.onTransition(index = 4, auto = true))
        // The next one plays from its start again.
        assertEquals(4, tracker.onEnded())
    }

    @Test
    fun aSeekToAnotherItemIsNotAFinish() {
        tracker.onPlaylistStarted(index = 3, progress = 0.0)
        tracker.onSeek(positionMs = 0)

        assertNull(tracker.onTransition(index = 5, auto = false))
        assertEquals(5, tracker.onEnded())
    }

    @Test
    fun endedCountsOnce() {
        tracker.onPlaylistStarted(index = 2, progress = 0.0)

        assertEquals(2, tracker.onEnded())
        assertNull(tracker.onEnded())
    }
}
