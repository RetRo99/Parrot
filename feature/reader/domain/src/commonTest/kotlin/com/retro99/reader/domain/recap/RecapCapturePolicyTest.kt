package com.retro99.reader.domain.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecapPageDwellTest {

    @Test
    fun pageIsDueOnceAfterTheDwell() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)
        dwell.show("p1", nowMs = 0)

        assertEquals(1_000, dwell.remainingMs(4_000))
        assertNull(dwell.takeDue(4_999))
        assertEquals("p1", dwell.takeDue(5_000))
        assertNull(dwell.takeDue(9_000))
        assertNull(dwell.remainingMs(9_000))
    }

    @Test
    fun restartDropsTheTimeAlreadySpent() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)
        dwell.show("p1", 0)
        dwell.setCounting(false, 4_000)
        dwell.setCounting(true, 10_000)
        dwell.restart(10_000)

        assertNull(dwell.takeDue(14_999))
        assertEquals("p1", dwell.takeDue(15_000))
    }

    @Test
    fun fastFlipsAreNeverDue() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)
        dwell.show("p1", 0)
        dwell.show("p2", 2_000)
        dwell.show("p3", 4_000)

        assertNull(dwell.takeDue(6_000))
        assertEquals("p3", dwell.takeDue(9_000))
    }

    @Test
    fun repeatedEmissionOfTheSamePageKeepsItsTime() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)
        dwell.show("p1", 0)
        dwell.show("p1", 4_000)

        assertEquals("p1", dwell.takeDue(5_000))
    }

    @Test
    fun returningToAPageStartsItsDwellAgain() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)
        dwell.show("p1", 0)
        assertEquals("p1", dwell.takeDue(5_000))
        dwell.show("p2", 6_000)
        dwell.show("p1", 7_000)

        assertNull(dwell.takeDue(11_000))
        assertEquals("p1", dwell.takeDue(12_000))
    }

    @Test
    fun timeWhileNotCountingIsExcluded() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)
        dwell.show("p1", 0)
        dwell.setCounting(false, 3_000)

        assertNull(dwell.remainingMs(60_000))
        assertNull(dwell.takeDue(60_000))

        dwell.setCounting(true, 60_000)
        assertEquals(2_000, dwell.remainingMs(60_000))
        assertEquals("p1", dwell.takeDue(62_000))
    }

    @Test
    fun pageShownWhileNotCountingWaitsForCounting() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)
        dwell.setCounting(false, 0)
        dwell.show("p1", 0)

        assertNull(dwell.takeDue(10_000))
        dwell.setCounting(true, 10_000)
        assertEquals("p1", dwell.takeDue(15_000))
    }

    @Test
    fun nothingShownIsNeverDue() {
        val dwell = RecapPageDwell<String>(dwellMs = 5_000)

        assertNull(dwell.remainingMs(10_000))
        assertNull(dwell.takeDue(10_000))
    }
}

class RecapActiveReadingClockTest {

    @Test
    fun countsOnlyActiveTime() {
        val clock = RecapActiveReadingClock(idleCapMs = 60_000)
        clock.setActive(true, 0)
        clock.onActivity(30_000)
        clock.setActive(false, 40_000)
        clock.setActive(true, 100_000)

        assertEquals(50_000, clock.totalMs(110_000))
    }

    @Test
    fun idleTimeBeyondTheCapIsNotCounted() {
        val clock = RecapActiveReadingClock(idleCapMs = 60_000)
        clock.setActive(true, 0)

        assertEquals(60_000, clock.totalMs(10 * 60_000))

        clock.onActivity(10 * 60_000)
        assertEquals(70_000, clock.totalMs(10 * 60_000 + 10_000))
    }

    @Test
    fun inactiveClockCountsNothing() {
        val clock = RecapActiveReadingClock()

        assertEquals(0, clock.totalMs(100_000))
    }

    @Test
    fun repeatedReadsDontDoubleCount() {
        val clock = RecapActiveReadingClock(idleCapMs = 60_000)
        clock.setActive(true, 0)

        assertEquals(10_000, clock.totalMs(10_000))
        assertEquals(10_000, clock.totalMs(10_000))
        assertEquals(20_000, clock.totalMs(20_000))
    }
}
