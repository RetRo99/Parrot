package com.retro99.reader.domain.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecapReadTrackerTest {
    @Test fun pageAndHeardSentenceShareCoordinatesInEitherOrder() {
        val tracker = RecapReadTracker()
        val piece = RecapTextPiece(0, 9, "She left.", false)
        assertEquals("She left.", tracker.takeUnreadPageText("c1", listOf(piece)))
        assertNull(tracker.takeUnheardSentence("c1", 0, "She left.", 0, "She left."))
        assertEquals("She left.", tracker.takeUnheardSentence("c2", 0, "She left.", 0, "She left."))
        assertNull(tracker.takeUnreadPageText("c2", listOf(piece)))
    }

    private val chapter = "Very long chapter text. ".repeat(20) + "The end."

    private fun piece(start: Int, end: Int, startsBlock: Boolean = false) =
        RecapTextPiece(start, end, chapter.substring(start, end), startsBlock)

    @Test
    fun capturesAPageOnce() {
        val tracker = RecapReadTracker()
        val page = listOf(piece(0, 48))

        assertEquals("Very long chapter text. Very long chapter text.", tracker.takeUnreadPageText("c1", page))
        assertNull(tracker.takeUnreadPageText("c1", page))
    }

    @Test
    fun goingBackAndRereadingAddsNothing() {
        val tracker = RecapReadTracker()
        tracker.takeUnreadPageText("c1", listOf(piece(0, 24)))
        tracker.takeUnreadPageText("c1", listOf(piece(24, 48)))

        assertNull(tracker.takeUnreadPageText("c1", listOf(piece(0, 24))))
        assertNull(tracker.takeUnreadPageText("c1", listOf(piece(24, 48))))
    }

    @Test
    fun overlappingPageKeepsOnlyTheNewPart() {
        val tracker = RecapReadTracker()
        tracker.takeUnreadPageText("c1", listOf(piece(0, 24)))

        // Relaid out after a font change: the page now starts earlier.
        assertEquals("Very long chapter text.", tracker.takeUnreadPageText("c1", listOf(piece(10, 48))))
    }

    @Test
    fun aSkippedRangeIsCapturedWhenReadLater() {
        val tracker = RecapReadTracker()
        tracker.takeUnreadPageText("c1", listOf(piece(0, 24)))
        tracker.takeUnreadPageText("c1", listOf(piece(48, 72)))

        assertEquals(
            "Very long chapter text.",
            tracker.takeUnreadPageText("c1", listOf(piece(0, 72))),
        )
    }

    @Test
    fun chaptersAreTrackedSeparately() {
        val tracker = RecapReadTracker()
        tracker.takeUnreadPageText("c1", listOf(piece(0, 24)))

        assertEquals("Very long chapter text.", tracker.takeUnreadPageText("c2", listOf(piece(0, 24))))
    }

    @Test
    fun joinsBlocksWithNewlinesAndInlineRunsDirectly() {
        val tracker = RecapReadTracker()
        val pieces = listOf(
            RecapTextPiece(0, 6, "Title ", startsBlock = false),
            RecapTextPiece(6, 13, "  It wa", startsBlock = true),
            RecapTextPiece(13, 23, "s raining.", startsBlock = false),
        )

        assertEquals("Title\nIt was raining.", tracker.takeUnreadPageText("c1", pieces))
    }

    @Test
    fun blankOrEmptyPageCapturesNothing() {
        val tracker = RecapReadTracker()

        assertNull(tracker.takeUnreadPageText("c1", emptyList()))
        assertNull(tracker.takeUnreadPageText("c1", listOf(RecapTextPiece(0, 3, " \n ", false))))
    }

    @Test
    fun mismatchedOffsetsAreUsedWholeOrNotAtAll() {
        val tracker = RecapReadTracker()
        tracker.takeUnreadPageText("c1", listOf(piece(0, 10)))

        assertNull(tracker.takeUnreadPageText("c1", listOf(RecapTextPiece(5, 20, "short", false))))
        assertEquals("fresh", tracker.takeUnreadPageText("c1", listOf(RecapTextPiece(30, 40, "fresh", false))))
    }

    @Test
    fun heardSentencesAreTakenOnce() {
        val tracker = RecapReadTracker()

        assertEquals("One two.", tracker.takeUnheardSentence("c1", 3, " One\n two. "))
        assertNull(tracker.takeUnheardSentence("c1", 3, "One two."))
        assertEquals("One two.", tracker.takeUnheardSentence("c2", 3, "One two."))
        assertNull(tracker.takeUnheardSentence("c1", 4, "   "))
    }
}

class RecapTextTest {

    @Test
    fun lastSentenceIsTheFinalOneOfTheLastParagraph() {
        val text = "Earlier page.\nShe left. He waited by the door! Then he “said” no."

        assertEquals("Then he “said” no.", RecapText.lastSentence(text))
    }

    @Test
    fun lastSentenceKeepsAFragmentWhereReadingStopped() {
        assertEquals("He opened the", RecapText.lastSentence("She left. He opened the"))
    }

    @Test
    fun quotedEndingsAndAbbreviationLikeDotsAreHandled() {
        assertEquals("“Go.”", RecapText.lastSentence("He said: “Stay.” “Go.”"))
        assertEquals("Pi is 3.14 today.", RecapText.lastSentence("Hi. Pi is 3.14 today."))
    }

    @Test
    fun longSentenceKeepsItsTailFromAWord() {
        val long = (1..200).joinToString(" ") { "word$it" } + "."
        val last = RecapText.lastSentence(long, maxChars = 50)!!

        assertEquals(true, last.length <= 50)
        assertEquals(true, last.endsWith("word200."))
        assertEquals(true, last.startsWith("word"))
    }

    @Test
    fun blankTextHasNoLastSentence() {
        assertNull(RecapText.lastSentence(null))
        assertNull(RecapText.lastSentence("  \n "))
    }
}

class RecapLanguagesTest {

    @Test
    fun bookLanguageWinsWhenSupported() {
        assertEquals("sl", RecapLanguages.resolve("sl-SI", "en_US"))
    }

    @Test
    fun unsupportedBookLanguageFallsBackToTheApp() {
        assertEquals("de", RecapLanguages.resolve("ja", "de-AT"))
        assertEquals("en", RecapLanguages.resolve(null, "EN"))
    }

    @Test
    fun nothingSupportedGivesNull() {
        assertNull(RecapLanguages.resolve("ja", "ko"))
        assertNull(RecapLanguages.resolve("", null))
    }
}
