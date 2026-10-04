package com.retro99.reader.ui.reader

import com.retro99.reader.domain.recap.RecapActiveReadingClock
import com.retro99.reader.domain.recap.RecapCapturePolicy
import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapReadTracker
import com.retro99.reader.domain.recap.RecapSessionRecorder
import com.retro99.reader.domain.recap.RecapText
import com.retro99.reader.domain.recap.RecapTextSource
import com.retro99.reader.ui.navigator.FinishedTtsSentence
import com.retro99.reader.ui.navigator.VisibleTextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Where the reader settled, as the recap capture sees it. */
internal data class RecapPage(
    val href: String,
    val chapter: RecapChapter?,
    val position: RecapPosition,
    /** Page within the chapter, from the locator's page calculation. */
    val chapterPage: Int? = null,
) {
    /** Two emissions at the same place are the same page. */
    val key: Pair<String, Double?> get() = href to position.progression
}

/** What the session end needs from capture. */
internal data class RecapCaptureSummary(
    val lastSentence: String?,
    val activeReadingMs: Long,
)

/**
 * Feeds one reading session's text to the recorder as it is read, so an
 * app kill still leaves completed text. Visible text is only a candidate:
 * it counts after it has been turned/scrolled past, never at session end.
 * Only created when cloud recaps are on. Call from the main thread.
 */
internal class ReaderRecapCapture(
    private val sessionId: String,
    private val recorder: RecapSessionRecorder,
    private val scope: CoroutineScope,
    private val readVisibleText: suspend () -> VisibleTextRange?,
    private val nowMs: () -> Long = monotonicMillis(),
    idleCapMs: Long = RecapCapturePolicy.IDLE_CAP_MS,
) {
    private val tracker = RecapReadTracker()
    private val clock = RecapActiveReadingClock(idleCapMs)

    private var page: RecapPage? = null
    private var pageGeneration = 0
    private var captureJob: Job? = null
    private var candidate: Pair<RecapPage, VisibleTextRange>? = null
    private var lastText: String? = null
    private var summary: RecapCaptureSummary? = null

    private var foreground = true
    private var playing = false
    private var blocked = false
    private var scrolling = false

    init {
        clock.setActive(true, nowMs())
    }

    fun onPageShown(next: RecapPage, navigated: Boolean = false, scrollMode: Boolean = false) {
        if (summary != null) return
        scrolling = scrollMode
        if (page?.key == next.key) return
        val now = nowMs()
        val previous = page
        val pending = candidate
        pageGeneration++
        captureJob?.cancel()
        candidate = null
        page = next
        clock.onActivity(now)
        val generation = pageGeneration
        if (!canCapturePages()) return
        captureJob = scope.launch {
            val range = try {
                readVisibleText()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (summary != null || generation != pageGeneration || !canCapturePages()) return@launch
            if (range.pieces.isEmpty()) return@launch
            if (next.chapterPage != null && range.page != null && next.chapterPage != range.page) return@launch
            // In scroll mode the locator can move several times within a
            // viewport. Keep the old range until it is entirely above view.
            val sameChapter = previous?.href == next.href
            val forward = previous?.position?.totalProgression?.let { old ->
                next.position.totalProgression?.let { it > old }
            } == true
            val adjacent = previous?.chapterPage?.let { old -> next.chapterPage == old + 1 } == true
            val chapterTurn = !sameChapter && previous?.chapter?.index?.let { old -> next.chapter?.index == old + 1 } == true &&
                next.chapterPage == 1
            if (!navigated && forward && pending != null && (scrollMode && sameChapter || adjacent || chapterTurn)) {
                // Range coordinates, rather than locator progression alone,
                // prove that every committed word is now behind the reader.
                val completed = if (sameChapter) pending.second.pieces.filter { it.end <= range.startOffset } else pending.second.pieces
                val text = tracker.takeUnreadPageText(pending.first.href, completed)
                if (text != null) append(text, pending.first.chapter, next.position, RecapTextSource.PAGE)
            }
            candidate = if (scrollMode && !navigated && forward && sameChapter && pending != null) {
                next to range.copy(pieces = (pending.second.pieces.filter { it.end > range.startOffset } + range.pieces)
                    .distinctBy { it.start to it.end }.sortedBy { it.start })
            } else next to range
        }
    }

    fun setForeground(value: Boolean) {
        val wasCounting = canCapturePages()
        foreground = value
        updateCounting(wasCounting)
    }

    /**
     * Device read-aloud captures sentences itself, so pages pause meanwhile.
     * [isPlaying] must stay true while it loads or synthesises, too.
     */
    fun setPlayback(isPlaying: Boolean, isDeviceVoice: Boolean) {
        val wasCounting = canCapturePages()
        playing = isPlaying
        updateCounting(wasCounting)
    }

    /** A startup prompt covers the page; it isn't being read. */
    fun setBlocked(value: Boolean) {
        val wasCounting = canCapturePages()
        blocked = value
        updateCounting(wasCounting)
    }

    fun onSentenceFinished(finished: FinishedTtsSentence) {
        if (summary != null) return
        clock.onActivity(nowMs())
        val current = page
        val href = finished.chapterHref?.substringBefore('#') ?: current?.href.orEmpty()
        val sentence = finished.sentence
        val text = tracker.takeUnheardSentence(href, sentence.index, sentence.text, sentence.startOffset, sentence.rawText)
            ?: return
        val sameChapter = current != null && current.href == href
        append(
            text = text,
            chapter = current?.chapter?.takeIf { sameChapter },
            position = current?.position?.takeIf { sameChapter },
            source = RecapTextSource.TTS_SENTENCE,
        )
    }

    /** Stops capture; later calls return the same summary. */
    fun stop(): RecapCaptureSummary {
        summary?.let { return it }
        captureJob?.cancel()
        captureJob = null
        candidate = null
        return RecapCaptureSummary(
            lastSentence = RecapText.lastSentence(lastText),
            activeReadingMs = clock.totalMs(nowMs()),
        ).also { summary = it }
    }

    private fun updateCounting(wasCounting: Boolean) {
        if (summary != null) return
        val now = nowMs()
        clock.setActive(foreground || playing, now)
        if (!canCapturePages()) {
            candidate = null
            captureJob?.cancel()
        } else if (!wasCounting) {
            val current = page
            page = null
            current?.let { onPageShown(it, scrollMode = scrolling) }
        }
    }

    private fun canCapturePages(): Boolean = foreground && !blocked && !playing

    private fun append(
        text: String,
        chapter: RecapChapter?,
        position: RecapPosition?,
        source: RecapTextSource,
    ) {
        lastText = text
        recorder.appendReadText(sessionId, text, chapter, position, source)
    }

    private companion object {
        fun monotonicMillis(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
