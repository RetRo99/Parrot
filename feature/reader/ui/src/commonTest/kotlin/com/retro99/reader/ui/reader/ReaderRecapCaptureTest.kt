package com.retro99.reader.ui.reader

import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapSessionRecorder
import com.retro99.reader.domain.recap.RecapTextPiece
import com.retro99.reader.domain.recap.RecapTextSource
import com.retro99.reader.ui.navigator.FinishedTtsSentence
import com.retro99.reader.ui.navigator.VisibleTextRange
import com.retro99.reader.ui.tts.TtsSentence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderRecapCaptureTest {

    private val chapterText = (0 until 40).joinToString(" ") { "Sentence $it." }

    private fun page(progression: Double, href: String = "c1.xhtml") = RecapPage(
        href = href,
        chapter = RecapChapter(index = 1, title = "One"),
        position = RecapPosition(href, progression, progression / 10),
    )

    private fun visible(start: Int, end: Int) = VisibleTextRange(
        listOf(RecapTextPiece(start, end, chapterText.substring(start, end), false)),
    )

    private class Harness(scope: TestScope) {
        val appended = mutableListOf<Triple<String, RecapPosition?, RecapTextSource>>()
        var screen: VisibleTextRange? = null
        var reads = 0
        val recorder = object : RecapSessionRecorder {
            override fun onSessionStarted(
                sessionId: String,
                serverId: String,
                bookId: String,
                startPosition: RecapPosition,
                chapter: RecapChapter?,
                language: String?,
            ) = Unit

            override fun appendReadText(
                sessionId: String,
                text: String,
                chapter: RecapChapter?,
                position: RecapPosition?,
                source: RecapTextSource,
            ) {
                appended += Triple(text, position, source)
            }

            override fun onSessionEnded(
                sessionId: String,
                endPosition: RecapPosition?,
                lastSentence: String?,
                activeReadingMs: Long,
            ) = Unit
        }
        val capture = ReaderRecapCapture(
            sessionId = "s1",
            recorder = recorder,
            scope = scope.backgroundScope,
            readVisibleText = {
                reads++
                screen
            },
            nowMs = { scope.testScheduler.currentTime },
            dwellMs = 5_000,
            idleCapMs = 60_000,
        )
    }

    @Test
    fun pageIsCapturedOnlyAfterTheDwell() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24)
        h.capture.onPageShown(page(0.1))

        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(h.appended.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Sentence 0. Sentence 1."), h.appended.map { it.first })
        assertEquals(RecapTextSource.PAGE, h.appended.single().third)
        assertEquals(0.1, h.appended.single().second?.progression)
    }

    @Test
    fun fastFlipsAreSkipped() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24)
        h.capture.onPageShown(page(0.1))
        advanceTimeBy(2_000)
        h.capture.onPageShown(page(0.2))
        advanceTimeBy(2_000)
        h.capture.onPageShown(page(0.3))
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(h.appended.isEmpty())
        assertEquals(0, h.reads)
    }

    @Test
    fun goingBackDoesNotDuplicateText() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24)
        h.capture.onPageShown(page(0.1))
        advanceTimeBy(5_001)
        h.screen = visible(24, 48)
        h.capture.onPageShown(page(0.2))
        advanceTimeBy(5_001)
        h.screen = visible(0, 24)
        h.capture.onPageShown(page(0.1))
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(2, h.appended.size)
        assertEquals(3, h.reads)
    }

    @Test
    fun nothingVisibleCapturesNothing() = runTest {
        val h = Harness(this)
        h.screen = null
        h.capture.onPageShown(page(0.5))
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(h.appended.isEmpty())
        assertNull(h.capture.stop().lastSentence)
    }

    @Test
    fun backgroundAndDeviceVoicePausePageCapture() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24)
        h.capture.onPageShown(page(0.1))
        advanceTimeBy(3_000)
        h.capture.setForeground(false)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.appended.isEmpty())

        h.capture.setForeground(true)
        h.capture.setPlayback(isPlaying = true, isDeviceVoice = true)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.appended.isEmpty())

        // After read-aloud the page needs a full dwell of its own.
        h.capture.setPlayback(isPlaying = false, isDeviceVoice = true)
        advanceTimeBy(2_001)
        runCurrent()
        assertTrue(h.appended.isEmpty())
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, h.appended.size)
    }

    @Test
    fun gapsInReadAloudDontAddUpToADwell() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 24)
        h.capture.onPageShown(page(0.1))
        repeat(4) {
            // Synthesis gap between sentences, then speaking again.
            advanceTimeBy(2_000)
            h.capture.setPlayback(isPlaying = true, isDeviceVoice = true)
            advanceTimeBy(4_000)
            h.capture.setPlayback(isPlaying = false, isDeviceVoice = true)
        }
        runCurrent()

        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun finishedSentencesAreAppendedOnceWithTheirChapter() = runTest {
        val h = Harness(this)
        h.capture.onPageShown(page(0.1))
        h.capture.setPlayback(isPlaying = true, isDeviceVoice = true)
        val sentence = TtsSentence(index = 4, elementId = "s4", text = "She left.")

        h.capture.onSentenceFinished(FinishedTtsSentence("c1.xhtml", sentence))
        h.capture.onSentenceFinished(FinishedTtsSentence("c1.xhtml", sentence))
        h.capture.onSentenceFinished(FinishedTtsSentence("c2.xhtml", sentence.copy(text = "Other.")))

        assertEquals(listOf("She left.", "Other."), h.appended.map { it.first })
        assertEquals(RecapTextSource.TTS_SENTENCE, h.appended.first().third)
        assertEquals("c1.xhtml", h.appended.first().second?.href)
        assertNull(h.appended.last().second)
    }

    @Test
    fun stopReportsTheLastSentenceAndActiveTimeAndEndsCapture() = runTest {
        val h = Harness(this)
        h.screen = visible(0, 36)
        h.capture.onPageShown(page(0.1))
        advanceTimeBy(5_001)
        runCurrent()
        h.capture.setForeground(false)
        advanceTimeBy(100_000)

        val summary = h.capture.stop()
        assertEquals("Sentence 2.", summary.lastSentence)
        assertEquals(5_001, summary.activeReadingMs)

        h.screen = visible(36, 60)
        h.capture.setForeground(true)
        h.capture.onPageShown(page(0.2))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, h.appended.size)
        assertEquals(summary, h.capture.stop())
    }

    @Test
    fun textFromAPageTheLocatorHasNotReachedIsDropped() = runTest {
        val h = Harness(this)
        // The WebView already shows page 3 while the locator still says 2.
        h.screen = visible(0, 24).copy(page = 3)
        h.capture.onPageShown(page(0.1).copy(chapterPage = 2))
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(h.appended.isEmpty())

        h.screen = visible(24, 48).copy(page = 3)
        h.capture.onPageShown(page(0.2).copy(chapterPage = 3))
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, h.appended.size)
    }

    @Test
    fun aPageChangeDuringTheReadDropsIt() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        val capture = ReaderRecapCapture(
            sessionId = "s1",
            recorder = h.recorder,
            scope = backgroundScope,
            readVisibleText = {
                gate.await()
                visible(0, 24)
            },
            nowMs = { testScheduler.currentTime },
            dwellMs = 5_000,
        )
        capture.onPageShown(page(0.1))
        advanceTimeBy(5_001)
        runCurrent()
        capture.onPageShown(page(0.2))
        gate.complete(Unit)
        runCurrent()

        assertTrue(h.appended.isEmpty())
    }
}
