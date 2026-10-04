package com.retro99.reader.ui.reader

import com.retro99.base.nowMillis
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reading-speed and pages-read accounting for one reader session.
 *
 * Holds the rules (see design/RECAP_REPORT.md §6) without any navigator dependency so they
 * can be unit tested:
 * - Only a single-page forward turn with a real reading dwell counts as a page read.
 *   Forward jumps (contents/search/bookmark), backward moves, fast flips and idle gaps
 *   only move the tracking state.
 * - Pages that turn themselves while narration or device TTS plays are not pages the
 *   reader read ([setListening] suspends counting).
 * - An estimate at or beyond the old clamp bounds is an artifact of those jumps and
 *   playback turns, not a measurement: it is never shown, never established and never
 *   persisted, so a spike can no longer pin 50/1000 WPM into settings.
 *
 * @param nowMs Monotonic-ish clock; injectable for tests.
 */
internal class ReadingSpeedEstimator(
    private val nowMs: () -> Long = { nowMillis() },
) {

    /** Cached word count for the current chapter */
    private var cachedChapterWordCount: Int? = null

    /** The href of the chapter for which we have cached data */
    private var cachedChapterHref: String? = null

    /** Cached words per page for the current chapter */
    private var cachedWordsPerPage: Double = 0.0

    /** Accumulated active reading time in milliseconds (excludes idle periods) */
    private var activeReadingTimeMs: Long = 0L

    /** Timestamp of the last page turn (used to detect idle periods) */
    private var lastPageTurnTimeMs: Long = 0L

    /** The page number at the last recorded page turn */
    private var lastRecordedPage: Int = 0

    /** Counted pages in the current chapter (resets on chapter change) */
    private var chapterPagesRead: Int = 0

    /** Counted pages in the whole session (survives chapter changes) */
    private var sessionPagesReadCount: Int = 0

    /** Dynamically calculated reading speed; only ever a plausible measurement */
    private var calculatedWordsPerMinute: Int? = null

    /**
     * Established reading speed that persists across chapter changes. Only plausible
     * measurements are ever written here, so it is safe to persist into settings.
     */
    private var establishedWordsPerMinute: Int? = null

    /** The last measurement was saturated (navigation/layout noise) and is not shown */
    private var measurementRejected: Boolean = false

    /** While true, page turns come from narration/TTS playback, not from the reader */
    private var listening: Boolean = false

    private val _establishedReadingSpeedWpm = MutableStateFlow<Int?>(null)

    /** Emits the established (confident) reading speed for persistence. */
    val establishedReadingSpeedWpm: StateFlow<Int?> = _establishedReadingSpeedWpm.asStateFlow()

    /** Pages read in this session: counted single-page forward turns only. */
    fun sessionPagesRead(): Int = sessionPagesReadCount

    /**
     * Narration and device TTS turn pages on their own; those turns are not reading.
     * Playback boundaries also cut any open dwell window in half otherwise.
     */
    fun setListening(value: Boolean) {
        if (listening == value) return
        listening = value
        lastPageTurnTimeMs = nowMs()
    }

    /**
     * Updates chapter caches and page accounting for a locator emission.
     *
     * @param trackSpeed When false, pages are still counted but no speed is measured and
     *   no estimate is built (reading-time display disabled, or audio-driven books).
     */
    fun onLocator(
        chapterHref: String,
        progression: Double?,
        chapterInfo: ChapterInfo?,
        fallbackWpm: Int,
        trackSpeed: Boolean = true,
    ): ChapterReadingTimeInfo? {
        val currentTimeMs = nowMs()
        val currentPage = chapterInfo?.currentPage ?: 1
        val totalPages = chapterInfo?.totalPages ?: 1
        val totalWords = chapterInfo?.totalWords

        // Update cached word count and reset tracking if chapter changed
        if (chapterHref != cachedChapterHref) {
            cachedChapterWordCount = totalWords
            cachedChapterHref = chapterHref
            resetChapter(currentTimeMs, currentPage)
            cachedWordsPerPage = wordsPerPage(cachedChapterWordCount, totalPages)
        } else if (totalWords != null && cachedChapterWordCount == null) {
            // Word count arrived after initial locator emission
            cachedChapterWordCount = totalWords
            cachedWordsPerPage = wordsPerPage(totalWords, totalPages)
        }

        if (chapterInfo != null) {
            recordPageTurn(currentTimeMs, currentPage)
            if (trackSpeed) {
                calculatedWordsPerMinute = updateSpeed()
            }
        }

        val displayWpm = when {
            !trackSpeed -> null
            calculatedWordsPerMinute != null -> calculatedWordsPerMinute
            measurementRejected -> null // never fall back on top of a saturated measurement
            else -> fallbackWpm
        }
        return buildReadingTimeInfo(
            chapterInfo = chapterInfo,
            chapterProgression = progression,
            wordsPerMinute = displayWpm,
        )
    }

    private fun wordsPerPage(totalWords: Int?, totalPages: Int): Double =
        if (totalPages > 0 && totalWords != null) totalWords.toDouble() / totalPages else 0.0

    /**
     * Resets chapter-specific tracking state.
     *
     * Preserves the established reading speed and the session page count across chapter
     * changes; the established speed is blended with new measurements from the new chapter.
     */
    private fun resetChapter(currentTimeMs: Long, currentPage: Int) {
        calculatedWordsPerMinute?.let { currentWpm ->
            establishedWordsPerMinute = currentWpm
        }
        activeReadingTimeMs = 0L
        lastPageTurnTimeMs = currentTimeMs
        lastRecordedPage = currentPage
        chapterPagesRead = 0
        measurementRejected = false
        calculatedWordsPerMinute = establishedWordsPerMinute
    }

    /**
     * Accounts the page change. Only a single-page forward turn with a reading dwell in
     * front of the reader counts; jumps, backward moves, fast flips, idle gaps and
     * playback turns just move the tracking state.
     *
     * @return true when the turn counted as a page read.
     */
    private fun recordPageTurn(currentTimeMs: Long, currentPage: Int): Boolean {
        val timeSinceLastPageTurn = currentTimeMs - lastPageTurnTimeMs
        val pagesMoved = currentPage - lastRecordedPage
        if (pagesMoved == 0) return false

        val isSinglePageForward = pagesMoved == 1
        val isWithinDwellWindow = timeSinceLastPageTurn in MIN_PAGE_DWELL_MS..MAX_PAGE_DWELL_MS
        val countsAsReading = isSinglePageForward && isWithinDwellWindow && !listening

        if (countsAsReading) {
            activeReadingTimeMs += timeSinceLastPageTurn
            chapterPagesRead += 1
            sessionPagesReadCount += 1
        }

        lastPageTurnTimeMs = currentTimeMs
        lastRecordedPage = currentPage
        return countsAsReading
    }

    /**
     * Calculates the user's reading speed from counted pages and active reading time.
     *
     * @return The display speed, or the previous value/null when there is no plausible
     *   measurement yet.
     */
    private fun updateSpeed(): Int? {
        // Need minimum active reading time and pages to calculate meaningful speed
        if (activeReadingTimeMs < MIN_READING_TIME_FOR_SPEED_CALC_MS ||
            chapterPagesRead < MIN_PAGES_FOR_SPEED_CALC
        ) {
            return calculatedWordsPerMinute
        }

        val wordsRead = (chapterPagesRead * cachedWordsPerPage).toInt()
        val activeMinutes = activeReadingTimeMs / 60_000.0
        if (activeMinutes <= 0 || wordsRead <= 0) {
            return calculatedWordsPerMinute
        }

        val rawWpm = (wordsRead / activeMinutes).toInt()

        // Blend with established reading speed if available.
        // This provides smoothing and prevents jarring changes between chapters.
        val blendedWpm = establishedWordsPerMinute?.let { established ->
            // Weight new measurement more as we accumulate more reading time in this chapter
            // After ~1 minute of reading, new measurement has ~50% weight
            val newMeasurementWeight = (activeMinutes / (activeMinutes + 1.0)).coerceIn(0.0, 0.8)
            val establishedWeight = 1.0 - newMeasurementWeight
            (established * establishedWeight + rawWpm * newMeasurementWeight).toInt()
        } ?: rawWpm

        if (!isPlausible(rawWpm) || !isPlausible(blendedWpm)) {
            // Saturated measurement: the old clamp's 50/1000 came from jumps and playback
            // turns. Treat it as no measurement at all instead of clipping it.
            measurementRejected = true
            return null
        }
        measurementRejected = false

        // Update established speed when we have a confident measurement (enough reading time)
        if (activeReadingTimeMs >= CONFIDENT_READING_TIME_MS) {
            establishedWordsPerMinute = blendedWpm
            if (_establishedReadingSpeedWpm.value != blendedWpm) {
                _establishedReadingSpeedWpm.value = blendedWpm
            }
        }

        return blendedWpm
    }

    /**
     * Builds the reading time info based on word count and reading progress.
     * Uses page-based calculation when available for more precise estimation.
     */
    private fun buildReadingTimeInfo(
        chapterInfo: ChapterInfo?,
        chapterProgression: Double?,
        wordsPerMinute: Int?,
    ): ChapterReadingTimeInfo? {
        val totalWords = cachedChapterWordCount
        if (totalWords == null || wordsPerMinute == null || !isPlausible(wordsPerMinute)) {
            return null
        }

        // Use page-based calculation if available (more precise)
        val remainingWords = if (chapterInfo != null && cachedWordsPerPage > 0) {
            val remainingPages = (chapterInfo.totalPages - chapterInfo.currentPage)
                .coerceAtLeast(0)
            (remainingPages * cachedWordsPerPage).toInt()
        } else if (chapterProgression != null) {
            // Fallback to progression-based calculation
            val remainingFraction = (1.0 - chapterProgression).coerceIn(0.0, 1.0)
            (totalWords * remainingFraction).toInt()
        } else {
            return null
        }

        val remainingMinutes = (remainingWords.toDouble() / wordsPerMinute).toInt()

        return ChapterReadingTimeInfo(
            remainingMinutes = remainingMinutes,
            remainingWords = remainingWords,
            totalWords = totalWords,
        )
    }

    companion object {
        /**
         * Minimum time in milliseconds a user must spend on a page for it to count
         * toward reading speed. Shorter gaps are treated as fast navigation/skimming.
         */
        const val MIN_PAGE_DWELL_MS = 10 * 1000L // 10 seconds

        /**
         * Maximum time in milliseconds a user can spend on a page for it to count
         * toward reading speed. Longer gaps are treated as idle time.
         */
        const val MAX_PAGE_DWELL_MS = 3 * 60 * 1000L // 3 minutes

        /** Minimum active reading time (ms) before calculating dynamic reading speed */
        const val MIN_READING_TIME_FOR_SPEED_CALC_MS = 10_000L // 10 seconds

        /**
         * Reading time (ms) required before we consider the measurement confident enough
         * to update the established reading speed. This prevents short bursts of reading
         * from overwriting a well-established reading speed.
         */
        const val CONFIDENT_READING_TIME_MS = 30_000L // 30 seconds

        /** Minimum pages read before calculating dynamic reading speed */
        const val MIN_PAGES_FOR_SPEED_CALC = 1

        /**
         * Plausible reading-speed band (WPM). The bounds are the estimator's old clamp
         * values, and a measurement landing on or beyond them is the clamp's artifact:
         * strictly inside is the only believable range.
         */
        const val MIN_REASONABLE_WPM = 50

        const val MAX_REASONABLE_WPM = 1000

        /** An estimate outside the plausible band is hidden instead of shown clipped. */
        fun isPlausible(wpm: Int): Boolean = wpm > MIN_REASONABLE_WPM && wpm < MAX_REASONABLE_WPM
    }
}
