package com.retro99.reader.ui.navigator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterSentenceExtractorTest {

    @Test
    fun `readable content check does not modify the document`() {
        // When
        val script = ChapterSentenceExtractor.getReadableContentCheckScript()

        // Then
        assertTrue(script.contains("textContent"))
        assertFalse(script.contains("buildSentenceSpans"))
        assertFalse(script.contains("extractContents"))
    }

    @Test
    fun `sentence extraction ignores unrelated sentence ids`() {
        // When
        val script = ChapterSentenceExtractor.getScript()

        // Then
        assertTrue(script.contains(".parrot-sentence"))
        assertTrue(script.contains("[id*=\".xhtml-sentence\"]"))
        assertFalse(script.contains("[id*=\"sentence\"]"))
    }

    @Test
    fun `parseResult decodes ordered Unicode sentences`() {
        // Given
        val encodedResult =
            """
                {
                    "status":"success",
                    "sentences":[
                        {"id":"sentence-1","t":"Hello%20world%21"},
                        {"id":"sentence-2","t":"%C5%BDivjo%2C%20svet%21"}
                    ]
                }
            """.trimIndent()

        // When
        val result = ChapterSentenceExtractor.parseResult(
            encodedResult,
        )

        // Then
        assertEquals(2, result.size)
        assertEquals(0, result[0].index)
        assertEquals("sentence-1", result[0].elementId)
        assertEquals("Hello world!", result[0].text)
        assertEquals(1, result[1].index)
        assertEquals("sentence-2", result[1].elementId)
        assertEquals("Živjo, svet!", result[1].text)
    }

    @Test
    fun `parseResult splits oversized sentences while preserving their element`() {
        // Given
        val longSentence = "a".repeat(600)
        val encodedResult =
            """
                {
                    "status":"success",
                    "sentences":[
                        {"id":"sentence-1","t":"$longSentence"}
                    ]
                }
            """.trimIndent()

        // When
        val result = ChapterSentenceExtractor.parseResult(encodedResult)

        // Then
        assertEquals(3, result.size)
        assertEquals(
            longSentence,
            result.joinToString(separator = "") { sentence -> sentence.text },
        )
        assertTrue(result.all { sentence -> sentence.elementId == "sentence-1" })
        assertTrue(result.all { sentence -> sentence.text.length <= 280 })
    }

}
