package com.retro99.reader.ui.reader

import com.retro99.reader.domain.recap.*
import com.retro99.reader.ui.navigator.FinishedTtsSentence
import com.retro99.reader.ui.navigator.VisibleTextRange
import com.retro99.reader.ui.tts.TtsSentence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderRecapCaptureTest {
    private val chapterText = (0 until 40).joinToString(" ") { "Sentence $it." }
    private fun page(number: Int) = RecapPage("c1.xhtml", RecapChapter(1, "One"),
        RecapPosition("c1.xhtml", number / 10.0, number / 100.0), chapterPage = number)
    private fun visible(start: Int, end: Int, number: Int? = null) = VisibleTextRange(
        listOf(RecapTextPiece(start, end, chapterText.substring(start, end), false)), number)

    private class Harness(scope: TestScope) {
        val appended = mutableListOf<Triple<String, RecapPosition?, RecapTextSource>>()
        var screen: VisibleTextRange? = null
        val recorder = object : RecapSessionRecorder {
            override fun onSessionStarted(sessionId: String, serverId: String, bookId: String,
                startPosition: RecapPosition, chapter: RecapChapter?, language: String?) = Unit
            override fun appendReadText(sessionId: String, text: String, chapter: RecapChapter?,
                position: RecapPosition?, source: RecapTextSource) { appended += Triple(text, position, source) }
            override fun onSessionEnded(sessionId: String, endPosition: RecapPosition?,
                lastSentence: String?, activeReadingMs: Long) = Unit
        }
        val capture = ReaderRecapCapture("s1", recorder, scope.backgroundScope, { screen },
            nowMs = { scope.testScheduler.currentTime })
    }

    @Test fun dwellAndCloseNeverCommitTheVisiblePage() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24, 1)
        h.capture.onPageShown(page(1))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.appended.isEmpty())
        assertNull(h.capture.stop().lastSentence)
    }

    @Test fun forwardTurnCommitsOnlyThePreviousPageEvenWithoutFiveSecondDwell() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24, 1)
        h.capture.onPageShown(page(1)); runCurrent()
        h.screen = visible(24, 48, 2)
        h.capture.onPageShown(page(2)); runCurrent()
        assertEquals(listOf("Sentence 0. Sentence 1."), h.appended.map { it.first })
        assertEquals(page(2).position, h.appended.single().second)
        assertEquals("Sentence 1.", h.capture.stop().lastSentence)
    }

    @Test fun jumpsAndBackwardTurnsDoNotCommitCandidates() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24, 1)
        h.capture.onPageShown(page(1)); runCurrent()
        h.screen = visible(48, 72, 8)
        h.capture.onPageShown(page(8), navigated = true); runCurrent()
        h.screen = visible(24, 48, 2)
        h.capture.onPageShown(page(2)); runCurrent()
        assertTrue(h.appended.isEmpty())
    }

    @Test fun scrollingDoesNotCommitTextStillOnScreen() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24)
        h.capture.onPageShown(page(1).copy(chapterPage = null), scrollMode = true); runCurrent()
        h.screen = visible(12, 36)
        h.capture.onPageShown(page(2).copy(chapterPage = null), scrollMode = true); runCurrent()
        assertTrue(h.appended.isEmpty())
        h.screen = visible(24, 48)
        h.capture.onPageShown(page(3).copy(chapterPage = null), scrollMode = true); runCurrent()
        assertEquals(listOf("Sentence 0. Sentence 1."), h.appended.map { it.first })
        h.capture.stop()
        assertEquals(1, h.appended.size)
    }

    @Test fun backgroundAndAudioDiscardTheUnfinishedManualPage() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24, 1)
        h.capture.onPageShown(page(1)); runCurrent()
        h.capture.setForeground(false)
        h.capture.setForeground(true)
        h.screen = visible(24, 48, 2)
        h.capture.onPageShown(page(2)); runCurrent()
        assertTrue(h.appended.isEmpty())
        h.capture.setPlayback(true, true)
        h.screen = visible(48, 72, 3)
        h.capture.onPageShown(page(3)); runCurrent()
        assertTrue(h.appended.isEmpty())
    }

    @Test fun finishedTtsSentencesCountOnceAndKeepTheirChapter() = runTest {
        val h = Harness(this)
        h.capture.onPageShown(page(1))
        h.capture.setPlayback(true, true)
        val sentence = TtsSentence(4, "s4", "She left.")
        h.capture.onSentenceFinished(FinishedTtsSentence("c1.xhtml", sentence))
        h.capture.onSentenceFinished(FinishedTtsSentence("c1.xhtml", sentence))
        assertEquals(listOf("She left."), h.appended.map { it.first })
        assertEquals(RecapTextSource.TTS_SENTENCE, h.appended.single().third)
        assertEquals("She left.", h.capture.stop().lastSentence)
    }

    @Test fun locatorMismatchAndLateDomResultsAreDiscarded() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24, 3)
        h.capture.onPageShown(page(1)); runCurrent()
        h.screen = visible(24, 48, 2)
        h.capture.onPageShown(page(2)); runCurrent()
        assertTrue(h.appended.isEmpty())
        val gate = CompletableDeferred<Unit>()
        val capture = ReaderRecapCapture("s2", h.recorder, backgroundScope, { gate.await(); visible(0, 24) })
        capture.onPageShown(page(1)); runCurrent()
        capture.stop(); gate.complete(Unit); runCurrent()
        assertTrue(h.appended.isEmpty())
    }
}
