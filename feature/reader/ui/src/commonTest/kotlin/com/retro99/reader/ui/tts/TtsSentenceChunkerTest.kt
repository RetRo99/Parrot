package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsSentenceChunkerTest {

    @Test
    fun `chunk keeps abbreviations and decimals inside their sentence`() {
        // Given
        val text = "Dr. Smith paid 3.50 dollars. Then he left!"

        // When
        val result = TtsSentenceChunker.chunk(
            text,
        )

        // Then
        assertEquals(
            listOf(
                "Dr. Smith paid 3.50 dollars.",
                "Then he left!",
            ),
            result,
        )
    }

    @Test
    fun `chunk splits long text at a clause boundary`() {
        // Given
        val firstClause = "a".repeat(180)
        val secondClause = "b".repeat(180)

        // When
        val result = TtsSentenceChunker.chunk("$firstClause, $secondClause")

        // Then
        assertEquals(2, result.size)
        assertTrue(result.all { chunk -> chunk.length <= 280 })
    }

    @Test
    fun `chunk hard splits long text without natural boundaries`() {
        // Given
        val text = "a".repeat(600)

        // When
        val result = TtsSentenceChunker.chunk(text)

        // Then
        assertEquals(3, result.size)
        assertEquals(text, result.joinToString(separator = ""))
        assertTrue(result.all { chunk -> chunk.length <= 280 })
    }

    @Test
    fun `chunk does not split an emoji surrogate pair at the hard boundary`() {
        // Given
        val emoji = "\uD83D\uDE00"
        val text = "a".repeat(279) + emoji + "b".repeat(300)

        // When
        val result = TtsSentenceChunker.chunk(text)

        // Then
        assertEquals(text, result.joinToString(separator = ""))
        assertEquals(279, result.first().length)
        assertTrue(result[1].startsWith(emoji))
        assertTrue(result.all { chunk -> chunk.length <= 280 })
    }
}
