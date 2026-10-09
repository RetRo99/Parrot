package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.TtsSentence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderTtsSentenceProgressTest {

    @Test
    fun `a sentence being read is shown counted from one`() = runTest {
        val recorded = Recorded()

        recorded.progress.onCurrentSentence(sentence(index = 88))

        assertEquals(88 + 1, recorded.sentenceNumbers.last())
    }

    // TTS-F24: the sheet kept showing "Sentence 89 of 97" after the engine had stopped.
    @Test
    fun `a stopped engine leaves no sentence position behind`() = runTest {
        val recorded = Recorded()

        recorded.progress.onCurrentSentence(sentence(index = 88))
        recorded.progress.onCurrentSentence(null)

        assertEquals(2, recorded.sentenceNumbers.size)
        assertNull(recorded.sentenceNumbers.last())
    }

    // Seen on the device in three runs: after "Stop listening" the last sentence stayed
    // highlighted on the page, because nothing ever removed the decoration.
    @Test
    fun `a stop takes the sentence highlight off the page`() = runTest {
        val recorded = Recorded()

        recorded.progress.onCurrentSentence(sentence(index = 88))
        recorded.progress.onCurrentSentence(null)

        assertEquals(1, recorded.highlightClears)
    }

    @Test
    fun `nothing is cleared before a sentence has been read`() = runTest {
        val recorded = Recorded()

        recorded.progress.onCurrentSentence(null)

        assertEquals(0, recorded.highlightClears)
    }

    @Test
    fun `a second stop does not clear the page again`() = runTest {
        val recorded = Recorded()

        recorded.progress.onCurrentSentence(sentence(index = 88))
        recorded.progress.onCurrentSentence(null)
        recorded.progress.onCurrentSentence(null)

        assertEquals(1, recorded.highlightClears)
    }

    private fun sentence(index: Int) = TtsSentence(
        index = index,
        elementId = "sentence-$index",
        text = "Text.",
    )

    private class Recorded {
        val sentenceNumbers = mutableListOf<Int?>()
        var highlightClears = 0
            private set

        val progress = ReaderTtsSentenceProgress(
            showSentenceNumber = { number -> sentenceNumbers += number },
            clearSentenceHighlight = { highlightClears++ },
        )
    }
}
