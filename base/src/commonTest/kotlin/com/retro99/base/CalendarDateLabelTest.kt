package com.retro99.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class CalendarDateLabelTest {
    @Test
    fun `recent date labels use calendar days in the local timezone`() {
        // Given
        val now = Instant.parse("2026-10-01T22:30:00Z").toEpochMilliseconds()
        // When / Then: it is already October 2 in Ljubljana.
        assertEquals(CalendarDateLabel.Today,
            calendarDateLabel("2026-10-02", now, "Europe/Ljubljana"))
        assertEquals(CalendarDateLabel.Today,
            calendarDateLabel("2026-10-02T12:30:00", now, "Europe/Ljubljana"))
        assertEquals(CalendarDateLabel.Yesterday,
            calendarDateLabel("2026-10-01T20:00:00Z", now, "Europe/Ljubljana"))
        assertEquals(CalendarDateLabel.Today,
            calendarDateLabel("2026-10-01T23:00:00+02:00", now, "UTC"))
    }

    @Test
    fun `yesterday remains yesterday across the daylight saving change`() {
        // Given: 25 hours later, but only one calendar day later.
        val now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilliseconds()
        // When
        val label = calendarDateLabel("2026-10-24T11:00:00Z", now, "Europe/Ljubljana")
        // Then
        assertEquals(CalendarDateLabel.Yesterday, label)
    }

    @Test
    fun `missing and invalid dates are hidden instead of showing raw ISO text`() {
        // Given / When / Then
        assertNull(calendarDateLabel(null))
        assertNull(calendarDateLabel(""))
        assertNull(calendarDateLabel("not-a-date"))
        assertNull(calendarDateLabel("2026-02-30"))
    }
}
