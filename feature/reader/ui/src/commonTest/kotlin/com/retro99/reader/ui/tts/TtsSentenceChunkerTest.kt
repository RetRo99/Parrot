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

    @Test
    fun `chunk returns nothing for an empty string`() {
        // When
        val result = TtsSentenceChunker.chunk("")

        // Then
        assertEquals(emptyList(), result)
    }

    @Test
    fun `chunk returns nothing for whitespace only`() {
        // When
        val result = TtsSentenceChunker.chunk("   \n\t  ")

        // Then
        assertEquals(emptyList(), result)
    }

    @Test
    fun `chunk keeps text with no terminator as one sentence`() {
        // When
        val result = TtsSentenceChunker.chunk("There is no terminator here")

        // Then
        assertEquals(listOf("There is no terminator here"), result)
    }

    @Test
    fun `chunk does not split after a price's decimals (current behaviour)`() {
        // When
        val result = TtsSentenceChunker.chunk("Dr. Smith paid \$3.50. He left.")

        // Then
        // The boundary after "$3.50." is lost: the token behind that full stop is read back only
        // to the decimal point, so "50" counts as a two-character initialism, i.e. an
        // abbreviation. Two sentences are spoken as one.
        assertEquals(listOf("Dr. Smith paid \$3.50. He left."), result)
    }

    @Test
    fun `chunk keeps a closing quote with the sentence it ends`() {
        // When
        val result = TtsSentenceChunker.chunk("\"Stop there.\" She turned away.")

        // Then
        assertEquals(listOf("\"Stop there.\"", "She turned away."), result)
    }

    @Test
    fun `chunk splits at three dots inside a sentence (current behaviour)`() {
        // When
        val result = TtsSentenceChunker.chunk("He paused ... then spoke again.")

        // Then
        // Spaced dots are each a terminator followed by a space, so one sentence becomes two
        // chunks and the second starts mid-clause. No text is lost.
        assertEquals(listOf("He paused ...", "then spoke again."), result)
    }

    @Test
    fun `chunk keeps a question mark and an exclamation mark together`() {
        // When
        val result = TtsSentenceChunker.chunk("Really?! I had no idea.")

        // Then
        assertEquals(listOf("Really?!", "I had no idea."), result)
    }

    @Test
    fun `chunk splits Cyrillic sentences at their full stop`() {
        // When
        val result = TtsSentenceChunker.chunk("Привет, мир. Как дела?")

        // Then
        assertEquals(listOf("Привет, мир.", "Как дела?"), result)
    }

    @Test
    fun `chunk does not split Japanese punctuation (current behaviour)`() {
        // When
        val result = TtsSentenceChunker.chunk("これは本です。それはペンですか？")

        // Then
        // The ideographic full stop and the full-width question mark are not terminators, and
        // Japanese puts no space after them, so two sentences stay one chunk. No text is lost.
        assertEquals(listOf("これは本です。それはペンですか？"), result)
    }

    @Test
    fun `chunk splits around numbers without splitting inside them`() {
        // When
        val result = TtsSentenceChunker.chunk("It cost 1,000.5 credits. Use v2.4.20 now.")

        // Then
        assertEquals(listOf("It cost 1,000.5 credits.", "Use v2.4.20 now."), result)
    }
}
