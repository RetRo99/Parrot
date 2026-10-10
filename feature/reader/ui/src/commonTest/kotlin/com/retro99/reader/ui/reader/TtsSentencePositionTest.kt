package com.retro99.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Listening sheet and the now-playing card show as a position (TTS-F24). */
class TtsSentencePositionTest {

    @Test
    fun `a counted chapter with a sentence being read shows its position`() {
        assertEquals(
            TtsSentencePosition(number = 89, count = 97),
            ttsSentencePosition(sentenceNumber = 89, sentenceCount = 97),
        )
    }

    @Test
    fun `no position while the chapter's sentences are not counted yet`() {
        assertNull(ttsSentencePosition(sentenceNumber = null, sentenceCount = 0))
    }

    @Test
    fun `no position when the engine is reading no sentence`() {
        assertNull(ttsSentencePosition(sentenceNumber = null, sentenceCount = 97))
    }

    @Test
    fun `a position never runs past the end of the chapter`() {
        assertEquals(
            TtsSentencePosition(number = 97, count = 97),
            ttsSentencePosition(sentenceNumber = 120, sentenceCount = 97),
        )
    }
}
