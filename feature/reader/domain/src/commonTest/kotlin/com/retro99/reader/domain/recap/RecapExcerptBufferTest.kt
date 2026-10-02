package com.retro99.reader.domain.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecapExcerptBufferTest {

    @Test
    fun appendsSegmentsInOrder() {
        val buffer = RecapExcerptBuffer()
        buffer.append("First page.")
        buffer.append("  Second   page. ")

        assertEquals("First page.\nSecond page.", buffer.text())
    }

    @Test
    fun ignoresBlankAndRepeatedSegments() {
        val buffer = RecapExcerptBuffer()
        assertTrue(buffer.append("Same page."))
        assertFalse(buffer.append("Same page."))
        assertFalse(buffer.append("   "))

        assertEquals("Same page.", buffer.text())
    }

    @Test
    fun neverDropsTextFromALongSession() {
        val buffer = RecapExcerptBuffer()
        repeat(50_000) { page -> buffer.append("Page $page has some sentences. It ends here.") }

        assertTrue(buffer.length > 2_000_000)
        assertTrue(buffer.text().startsWith("Page 0 has some sentences."))
        assertTrue(buffer.text().endsWith("Page 49999 has some sentences. It ends here."))
    }

    @Test
    fun keepsOneHugeSegmentWhole() {
        val buffer = RecapExcerptBuffer()
        val huge = (1..100_000).joinToString(" ") { "w$it" }
        buffer.append(huge)

        assertEquals(huge, buffer.text())
    }

    @Test
    fun restoresFromPersistedText() {
        val buffer = RecapExcerptBuffer(initial = "Saved text.")
        buffer.append("More.")

        assertEquals("Saved text.\nMore.", buffer.text())
    }
}
