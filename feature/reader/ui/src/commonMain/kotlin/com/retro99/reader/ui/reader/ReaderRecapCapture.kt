package com.retro99.reader.ui.reader

import com.retro99.reader.domain.recap.RecapActiveReadingClock
import com.retro99.reader.domain.recap.RecapCapturePolicy
import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapPageDwell
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapReadTracker
import com.retro99.reader.domain.recap.RecapSessionRecorder
import com.retro99.reader.domain.recap.RecapText
import com.retro99.reader.domain.recap.RecapTextSource
import com.retro99.reader.ui.navigator.FinishedTtsSentence
import com.retro99.reader.ui.navigator.VisibleTextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Where the reader settled, as the recap capture sees it. */
internal data class RecapPage(
    val href: String,
    val chapter: RecapChapter?,
    val position: RecapPosition,
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
 * app kill still leaves the text read so far. A page counts after it has
 * been visible for the dwell time; a spoken sentence once its audio ends.
 * Only created when cloud recaps are on. Call from the main thread.
 */
internal class ReaderRecapCapture(
    private val sessionId: String,
    private val recorder: RecapSessionRecorder,
    private val scope: CoroutineScope,
    private val readVisibleText: suspend () -> VisibleTextRange?,
    private val nowMs: () -> Long = monotonicMillis(),
    dwellMs: Long = RecapCapturePolicy.PAGE_DWELL_MS,
    idleCapMs: Long = RecapCapturePolicy.IDLE_CAP_MS,
) {
    private val dwell = RecapPageDwell<Pair<String, Double?>>(dwellMs)
    private val tracker = RecapReadTracker()
    private val clock = RecapActiveReadingClock(idleCapMs)

    private var page: RecapPage? = null
    private var pageGeneration = 0
    private var dwellJob: Job? = null
    private var captureJob: Job? = null
    private var lastText: String? = null
    private var summary: RecapCaptureSummary? = null

    private var foreground = true
    private var playing = false
    private var deviceVoice = false
    private var blocked = false
    private var readingAloud = false

    init {
        clock.setActive(true, nowMs())
    }

    fun onPageShown(next: RecapPage) {
        if (summary != null) return
        val now = nowMs()
        if (page?.key != next.key) pageGeneration++
        page = next
        clock.onActivity(now)
        dwell.show(next.key, now)
        scheduleDwell()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        updateCounting()
    }

    /**
     * Device read-aloud captures sentences itself, so pages pause meanwhile.
     * [isPlaying] must stay true while it loads or synthesises, too.
     */
    fun setPlayback(isPlaying: Boolean, isDeviceVoice: Boolean) {
        playing = isPlaying
        deviceVoice = isDeviceVoice
        updateCounting()
    }

    /** A startup prompt covers the page; it isn't being read. */
    fun setBlocked(value: Boolean) {
        blocked = value
        updateCounting()
    }

    fun onSentenceFinished(finished: FinishedTtsSentence) {
        if (summary != null) return
        clock.onActivity(nowMs())
        val current = page
        val href = finished.chapterHref?.substringBefore('#') ?: current?.href.orEmpty()
        val text = tracker.takeUnheardSentence(href, finished.sentence.index, finished.sentence.text)
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
        dwellJob?.cancel()
        dwellJob = null
        captureJob?.cancel()
        captureJob = null
        return RecapCaptureSummary(
            lastSentence = RecapText.lastSentence(lastText),
            activeReadingMs = clock.totalMs(nowMs()),
        ).also { summary = it }
    }

    private fun updateCounting() {
        if (summary != null) return
        val now = nowMs()
        clock.setActive(foreground || playing, now)
        val aloud = playing && deviceVoice
        dwell.setCounting(foreground && !blocked && !aloud, now)
        // Time on the page before or during read-aloud wasn't reading it.
        if (readingAloud && !aloud) dwell.restart(now)
        readingAloud = aloud
        scheduleDwell()
    }

    private fun scheduleDwell() {
        dwellJob?.cancel()
        dwellJob = null
        if (dwell.remainingMs(nowMs()) == null) return
        dwellJob = scope.launch {
            while (true) {
                val remaining = dwell.remainingMs(nowMs()) ?: return@launch
                if (remaining > 0) {
                    delay(remaining)
                    continue
                }
                val key = dwell.takeDue(nowMs()) ?: return@launch
                // Own job: a later reschedule mustn't cancel a read in flight.
                captureJob = scope.launch { capturePage(key) }
                return@launch
            }
        }
    }

    private suspend fun capturePage(key: Pair<String, Double?>) {
        val dwelt = page?.takeIf { it.key == key } ?: return
        val generation = pageGeneration
        val range = try {
            readVisibleText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        // The reader moved on while the page was read: its text is unsure.
        if (summary != null || generation != pageGeneration) return
        val text = tracker.takeUnreadPageText(dwelt.href, range.pieces) ?: return
        append(text, dwelt.chapter, dwelt.position, RecapTextSource.PAGE)
    }

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
