package com.retro99.reader.ui.navigator

import com.retro99.reader.domain.recap.RecapTextPiece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisibleTextRangeDetectorTest {

    @Test
    fun `script reads the DOM without changing it or falling back`() {
        // When
        val script = VisibleTextRangeDetector.getScript()

        // Then
        assertFalse(script.contains("extractContents"))
        assertFalse(script.contains("insertNode"))
        assertFalse(script.contains("lastSentenceId"))
        assertFalse(script.contains("%MAX_CHARS%"))
        assertTrue(script.contains("status: 'none'"))
    }

    @Test
    fun `parseResult decodes pieces in order with chapter offsets`() {
        // Given
        val json =
            """
                {"status":"found","pieces":[
                    {"s":120,"e":131,"b":0,"t":"Hello%20world"},
                    {"s":140,"e":153,"b":1,"t":"%C5%BDivjo%2C%20svet%21"}
                ]}
            """.trimIndent()

        // When
        val range = VisibleTextRangeDetector.parseResult(json)

        // Then
        assertEquals(
            listOf(
                RecapTextPiece(120, 131, "Hello world", startsBlock = false),
                RecapTextPiece(140, 153, "Živjo, svet!", startsBlock = true),
            ),
            range?.pieces,
        )
        assertEquals(120, range?.startOffset)
        assertEquals(153, range?.endOffset)
    }

    @Test
    fun `nothing visible gives null instead of a fallback`() {
        assertNull(VisibleTextRangeDetector.parseResult("""{"status":"none"}"""))
        assertNull(VisibleTextRangeDetector.parseResult("""{"status":"error"}"""))
        assertNull(VisibleTextRangeDetector.parseResult("""{"status":"found","pieces":[]}"""))
    }

    @Test
    fun `empty or inverted pieces are dropped`() {
        // Given
        val json = """{"status":"found","pieces":[{"s":5,"e":5,"t":""},{"s":9,"e":3,"t":"x"}]}"""

        // Then
        assertNull(VisibleTextRangeDetector.parseResult(json))
    }

    @Test
    fun `toString never includes the text`() {
        // Given
        val range = VisibleTextRange(listOf(RecapTextPiece(0, 6, "Secret", false)))

        // Then
        assertFalse(range.toString().contains("Secret"))
        assertFalse(range.pieces.first().toString().contains("Secret"))
    }
}
