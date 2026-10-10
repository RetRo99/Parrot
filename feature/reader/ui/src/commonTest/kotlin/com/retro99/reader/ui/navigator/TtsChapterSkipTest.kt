package com.retro99.reader.ui.navigator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pressing play where there is nothing to read (TTS-F14). The book is a list of chapters,
 * each either with text or without; the reader starts on the first of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsChapterSkipTest {

    @Test
    fun `a chapter that has text starts in place`() = runTest {
        val book = FakeBook(chaptersWithText = listOf(true, true))

        val reason = book.pressPlay(sentenceIndex = 7)

        assertNull(reason)
        assertEquals(listOf(7), book.startedSentenceIndices)
        assertEquals(0, book.chapterMoves)
    }

    @Test
    fun `an empty chapter starts the next chapter with text at its first sentence`() = runTest {
        val book = FakeBook(chaptersWithText = listOf(false, true))

        val reason = book.pressPlay(sentenceIndex = 7)

        assertNull(reason)
        assertEquals(listOf(0), book.startedSentenceIndices)
        assertEquals(1, book.chapterMoves)
    }

    @Test
    fun `two empty chapters in a row are both skipped`() = runTest {
        val book = FakeBook(chaptersWithText = listOf(false, false, true))

        val reason = book.pressPlay()

        assertNull(reason)
        assertEquals(listOf(0), book.startedSentenceIndices)
        assertEquals(2, book.chapterMoves)
        assertEquals(2, book.currentChapter)
    }

    @Test
    fun `an empty chapter with no text after it fails once and starts nothing`() = runTest {
        val book = FakeBook(chaptersWithText = listOf(false, false))

        val reason = book.pressPlay()

        assertEquals(TtsPlaybackFailureReason.CONTENT_UNAVAILABLE, reason)
        assertEquals(0, book.startedSentenceIndices.size)
        // Walked to the end of the book before giving up, and stopped there.
        assertEquals(1, book.chapterMoves)
        assertEquals(1, book.currentChapter)
    }

    @Test
    fun `the walk is bounded when every move reports a chapter and none has text`() = runTest {
        var moves = 0

        val reason = startAtFirstChapterWithText(
            maxChapterMoves = 3,
            hasSentencesHere = { false },
            startHere = { null },
            goToNextChapter = {
                moves++
                true
            },
            startAtChapterStart = { null },
        )

        assertEquals(TtsPlaybackFailureReason.CONTENT_UNAVAILABLE, reason)
        assertEquals(3, moves)
    }

    @Test
    fun `a stop cancels a skip in flight`() = runTest {
        val move = CompletableDeferred<Boolean>()
        var starts = 0
        val job = backgroundScope.launch {
            startAtFirstChapterWithText(
                maxChapterMoves = 10,
                hasSentencesHere = { false },
                startHere = { null },
                goToNextChapter = { move.await() },
                startAtChapterStart = {
                    starts++
                    null
                },
            )
        }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled, "the skip should be cancelled")
        assertEquals(0, starts)
    }
}

/**
 * A book of chapters, some with text. Mirrors the controller: the sentences it holds belong
 * to the chapter it is on, and a chapter move clears them.
 */
private class FakeBook(private val chaptersWithText: List<Boolean>) {

    var currentChapter = 0
        private set
    var chapterMoves = 0
        private set
    val startedSentenceIndices = mutableListOf<Int>()

    private var loadedChapter: Int? = null

    suspend fun pressPlay(sentenceIndex: Int? = null): TtsPlaybackFailureReason? =
        startAtFirstChapterWithText(
            maxChapterMoves = chaptersWithText.size,
            hasSentencesHere = { loadSentences() },
            startHere = { start(sentenceIndex ?: 0) },
            goToNextChapter = { goToNextChapter() },
            startAtChapterStart = { start(0) },
        )

    private fun loadSentences(): Boolean {
        if (!chaptersWithText[currentChapter]) return false
        loadedChapter = currentChapter
        return true
    }

    private fun goToNextChapter(): Boolean {
        if (currentChapter + 1 >= chaptersWithText.size) return false
        currentChapter++
        chapterMoves++
        loadedChapter = null
        return true
    }

    private fun start(sentenceIndex: Int): TtsPlaybackFailureReason? {
        if (loadedChapter != currentChapter) {
            return TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
        }
        startedSentenceIndices += sentenceIndex
        return null
    }
}
