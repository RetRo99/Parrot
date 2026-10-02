package com.retro99.statistics.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveSessionTimerTest {
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
}
