package com.retro99.statistics.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveSessionTimerTest {
    @Test
    fun foregroundAndBackgroundPlaybackTransitionsCountOnlyActiveIntervals() {
        var elapsed = 0L
        val timer = ActiveSessionTimer { elapsed }
        timer.setActive(true) // Foreground reading.
        elapsed = 1_000L
        timer.setActive(false) // Background without playback.
        elapsed = 20_000L
        timer.setActive(true) // Notification starts background playback.
        elapsed = 23_000L
        timer.setActive(true) // Foreground again while still playing.
        elapsed = 25_000L
        timer.setActive(false) // Background and paused.
        elapsed = 100_000L
        assertEquals(6_000L, timer.finish())
        assertNull(timer.finish())
    }

    @Test
    fun backwardsClockSamplesCannotSubtractPreviouslyRecordedTime() {
        var elapsed = 100L
        val timer = ActiveSessionTimer { elapsed }
        timer.setActive(true)
        elapsed = 200L
        timer.setActive(false)
        timer.setActive(true)
        elapsed = 50L
        assertEquals(100L, timer.finish())
    }
    @Test
    fun pausesExcludeBackgroundTimeAndFinishingIsIdempotent() {
        var elapsed = 0L
        val timer = ActiveSessionTimer { elapsed }
        timer.setActive(true)
        elapsed = 1_000
        timer.setActive(true) // Duplicate lifecycle/playback notifications must not reset it.
        elapsed = 2_000
        timer.setActive(false)
        elapsed = 100_000
        timer.setActive(false)
        timer.setActive(true)
        elapsed = 103_000
        assertEquals(5_000L, timer.finish())
        assertNull(timer.finish()) // close() followed by onCleared().
        timer.setActive(true)
        assertNull(timer.finish())
    }

    @Test
    fun unopenedSessionRecordsNoTime() {
        val timer = ActiveSessionTimer { 500L }
        assertEquals(0L, timer.finish())
    }

    @Test
    fun finishingWhilePausedDoesNotIncludeTheTimeSinceThePause() {
        var elapsed = 10L
        val timer = ActiveSessionTimer { elapsed }
        timer.setActive(true)
        elapsed = 210L
        timer.setActive(false)
        elapsed = 1_000_000L
        assertEquals(200L, timer.finish())
    }

    @Test
    fun manyPauseResumeCyclesSumOnlyActiveIntervals() {
        var elapsed = 0L
        val timer = ActiveSessionTimer { elapsed }
        repeat(100) {
            timer.setActive(true)
            elapsed += 10L
            timer.setActive(false)
            elapsed += 1_000L
        }
        assertEquals(1_000L, timer.finish())
    }

    @Test
    fun finishingBeforeOpeningPreventsLateCallbacksFromStartingASession() {
        var elapsed = 0L
        val timer = ActiveSessionTimer { elapsed }
        assertEquals(0L, timer.finish())
        timer.setActive(true)
        elapsed = 10_000L
        timer.setActive(false)
        assertNull(timer.finish())
    }
}
