package com.retro99.books.ui.positions

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionPresentationTest {
    @Test fun `quote is the sentence crossing the anchor without surrounding markers`() {
        assertEquals("It was not mine to open, he said at last.", positionSentence(
            "Earlier words. It was not mine", " to open, he said at last. Another sentence."))
    }
    @Test fun `audio clock retains seconds and book hours`() {
        assertEquals("4:12:08", clockTime(15_128_000))
        assertEquals("0:00:00", clockTime(-1))
    }
}
