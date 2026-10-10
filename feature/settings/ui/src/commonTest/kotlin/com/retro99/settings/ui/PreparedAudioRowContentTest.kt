package com.retro99.settings.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Read aloud tab's prepared-audio row: its total, and when Delete all is offered. */
class PreparedAudioRowContentTest {

    @Test
    fun nothingPreparedSaysSoAndOffersNoDeletion() {
        val content = preparedAudioRowContent(0)
        assertNull(content.sizeLabel)
        assertFalse(content.canDeleteAll)
        assertFalse(preparedAudioRowContent(-1).canDeleteAll)
    }

    @Test
    fun aTotalIsShownWithDeleteAll() {
        val content = preparedAudioRowContent(2_450_000)
        assertEquals("2.5 MB", content.sizeLabel)
        assertTrue(content.canDeleteAll)
        assertEquals("940 kB", preparedAudioRowContent(940_000).sizeLabel)
    }
}
