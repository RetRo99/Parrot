package com.retro99.reader.domain.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecapExcerptBufferTest {

    @Test
    fun appendsSegmentsInOrder() {
        val buffer = RecapExcerptBuffer(maxChars = 100)
        buffer.append("First page.")
        buffer.append("  Second   page. ")

        assertEquals("First page.\nSecond page.", buffer.text())
    }

    @Test
    fun ignoresBlankAndRepeatedSegments() {
        val buffer = RecapExcerptBuffer(maxChars = 100)
        assertTrue(buffer.append("Same page."))
        assertFalse(buffer.append("Same page."))
        assertFalse(buffer.append("   "))

        assertEquals("Same page.", buffer.text())
    }

    @Test
    fun overflowDropsTheOldestTextAtASentenceBoundary() {
        val buffer = RecapExcerptBuffer(maxChars = 40)
        buffer.append("Old one. Old two.")
        buffer.append("Newer text here. Newest text.")

        val text = buffer.text()
        assertTrue(text.length <= 40)
        assertTrue(text.endsWith("Newest text."))
        assertTrue(text.startsWith("Old two.") || text.startsWith("Newer"), text)
    }

    @Test
    fun neverExceedsTheCapWithOneHugeSegment() {
        val buffer = RecapExcerptBuffer(maxChars = 8_000)
        val huge = (1..3_000).joinToString(" ") { "w$it" }
        buffer.append(huge)

        assertTrue(buffer.length <= 8_000)
        assertTrue(buffer.text().endsWith("w3000"))
    }

    @Test
    fun keepsTheMostRecentTextAcrossManyAppends() {
        val buffer = RecapExcerptBuffer()
        repeat(500) { page -> buffer.append("Page $page has some sentences. It ends here.") }

        assertTrue(buffer.length <= RecapLimits.MAX_EXCERPT_CHARS)
        assertTrue(buffer.text().endsWith("Page 499 has some sentences. It ends here."))
        assertFalse(buffer.text().contains("Page 1 has"))
    }

    @Test
    fun restoresFromPersistedText() {
        val buffer = RecapExcerptBuffer(maxChars = 50, initial = "Saved text.")
        buffer.append("More.")

        assertEquals("Saved text.\nMore.", buffer.text())
    }
}
