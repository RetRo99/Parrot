package com.retro99.base

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MediumDateFormatTest {
    @Test
    fun `language codes use localized display names`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("English", languageDisplayName("en"))
            assertEquals("English", languageDisplayName("en_US"))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("Englisch", languageDisplayName("en"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `older dates use the locale medium format`() {
        // Given
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilliseconds()
            // When
            val label = calendarDateLabel("2026-10-01", now, "UTC")
            // Then
            assertEquals(CalendarDateLabel.Medium("Oct 1, 2026"), label)
            assertEquals(CalendarDateLabel.Medium("Jan 20, 2026"),
                calendarDateLabel("2026-01-20", now, "UTC"))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("01.10.2026", formatMediumDate(2026, 10, 1))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
