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
    fun longSessionKeepsOpeningAndLatestTextWithinBudget() {
        val buffer = RecapExcerptBuffer()
        repeat(50_000) { page -> buffer.append("Page $page has some sentences. It ends here.") }

        assertTrue(buffer.length <= RecapLimits.MAX_EXCERPT_CHARS)
        assertTrue(buffer.text().startsWith("Page 0 has some sentences."))
        assertTrue(buffer.text().endsWith("Page 49999 has some sentences. It ends here."))
    }

    @Test
    fun hugeSegmentIsBoundedAndKeepsTheEnd() {
        val buffer = RecapExcerptBuffer()
        val huge = (1..100_000).joinToString(" ") { "w$it" }
        buffer.append(huge)

        assertTrue(buffer.length <= RecapLimits.MAX_EXCERPT_CHARS)
        assertTrue(buffer.text().startsWith("w1 w2 w3"))
        assertTrue(buffer.text().endsWith("w99999 w100000"))
    }

    @Test
    fun restoresFromPersistedText() {
        val buffer = RecapExcerptBuffer(initial = "Saved text.")
        buffer.append("More.")

        assertEquals("Saved text.\nMore.", buffer.text())
    }
}
