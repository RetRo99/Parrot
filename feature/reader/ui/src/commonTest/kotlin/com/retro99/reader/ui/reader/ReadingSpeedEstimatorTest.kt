package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.ChapterInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The estimator rules from design/RECAP_REPORT.md §6: jumps and playback turns are not
 * pages read, and a saturated estimate is never shown, established or persisted.
 */
class ReadingSpeedEstimatorTest {

    private var now = 0L
    private val estimator = ReadingSpeedEstimator { now }

    @Test
    fun `single page turns with a reading dwell count as pages read`() {
        // Given: 200 words per page, one page turned every 20s
        page(page = 1, atMs = 0)
        page(page = 2, atMs = 20_000)
        page(page = 3, atMs = 45_000)

        // Then
        assertEquals(2, estimator.sessionPagesRead())
        val established = estimator.establishedReadingSpeedWpm.value
        assertNotNull(established)
        assertTrue(ReadingSpeedEstimator.isPlausible(established))
    }

    @Test
    fun `a forward jump is not a page read`() {
        // Given: a contents/search/bookmark jump of five pages
        page(page = 1, atMs = 0)

        // When
        page(page = 6, atMs = 20_000)

        // Then
        assertEquals(0, estimator.sessionPagesRead())
        assertNull(estimator.establishedReadingSpeedWpm.value)
    }

    @Test
    fun `a fast flip is not a page read`() {
        // Given
        page(page = 1, atMs = 0)

        // When: flipped past after 3s
        page(page = 2, atMs = 3_000)

        // Then
        assertEquals(0, estimator.sessionPagesRead())
    }

    @Test
    fun `a page after an idle gap is not a page read`() {
        // Given
        page(page = 1, atMs = 0)

        // When: the reader comes back after more than three minutes
        page(page = 2, atMs = 200_000)

        // Then
        assertEquals(0, estimator.sessionPagesRead())
    }

    @Test
    fun `a backward move is not a page read`() {
        // Given
        page(page = 2, atMs = 0)

        // When
        page(page = 1, atMs = 20_000)

        // Then
        assertEquals(0, estimator.sessionPagesRead())
    }

    @Test
    fun `playback page turns are not pages read`() {
        // Given: device TTS is playing and turns pages on its own
        page(page = 1, atMs = 0)
        estimator.setListening(true)

        // When
        page(page = 2, atMs = 25_000)

        // Then
        assertEquals(0, estimator.sessionPagesRead())

        // When: playback stopped and the reader turns a page
        estimator.setListening(false)
        page(page = 3, atMs = 60_000)

        // Then: only the manual turn counts
        assertEquals(1, estimator.sessionPagesRead())
    }

    @Test
    fun `a saturated estimate is hidden instead of shown clipped`() {
        // Given: 10,000 words per page makes any measurement explode past 1000 wpm
        page(page = 1, atMs = 0, totalWords = 100_000, totalPages = 10)

        // When
        val info = page(page = 2, atMs = 20_000, totalWords = 100_000, totalPages = 10)

        // Then: no estimate (not even the fallback), and nothing to persist
        assertNull(info)
        assertNull(estimator.establishedReadingSpeedWpm.value)
    }

    @Test
    fun `an implausible fallback speed shows no estimate`() {
        // Given: a settings value pinned at the old clamp bound
        val info = page(page = 1, atMs = 0, fallbackWpm = 1000)

        // Then
        assertNull(info)
    }

    @Test
    fun `smoothing cannot hide an implausible raw measurement`() {
        page(page = 1, atMs = 0)
        page(page = 2, atMs = 20_000)
        page(page = 3, atMs = 40_000)
        val previous = estimator.establishedReadingSpeedWpm.value
        assertNotNull(previous)
        page(href = "ch2", page = 1, atMs = 41_000, totalWords = 4_000, totalPages = 10)
        val info = page(href = "ch2", page = 2, atMs = 61_000, totalWords = 4_000, totalPages = 10)
        assertNull(info)
        assertEquals(previous, estimator.establishedReadingSpeedWpm.value)
    }

    @Test
    fun `before any measurement the estimate falls back to settings`() {
        // When
        val info = page(page = 1, atMs = 0, fallbackWpm = 200)

        // Then
        assertNotNull(info)
    }

    @Test
    fun `pages read survives a chapter change`() {
        // Given: one counted page in the first chapter
        page(page = 1, atMs = 0)
        page(page = 2, atMs = 20_000)

        // When: the next chapter starts and its first page is turned past
        page(href = "ch2", page = 1, atMs = 21_000)
        page(href = "ch2", page = 2, atMs = 45_000)

        // Then
        assertEquals(2, estimator.sessionPagesRead())
    }

    @Test
    fun `pages are counted without measuring speed when tracking is off`() {
        // Given
        page(page = 1, atMs = 0, trackSpeed = false)

        // When
        val info = page(page = 2, atMs = 20_000, trackSpeed = false)

        // Then
        assertEquals(1, estimator.sessionPagesRead())
        assertNull(info)
        assertNull(estimator.establishedReadingSpeedWpm.value)
    }

    @Test
    fun `single page geometry uses progression for time remaining`() {
        val info = estimator.onLocator(
            chapterHref = "ch1",
            progression = 0.3,
            chapterInfo = ChapterInfo(1, 1, 2000),
            fallbackWpm = 200,
        )
        assertNotNull(info)
        assertEquals(1400, info.remainingWords)
        assertEquals(7, info.remainingMinutes)
    }

    @Test
    fun `reflow updates remaining words without counting a page turn`() {
        page(page = 1, atMs = 0)
        val info = page(page = 2, atMs = 60_000, totalPages = 20)
        assertNotNull(info)
        assertEquals(1800, info.remainingWords)
        assertEquals(9, info.remainingMinutes)
        assertEquals(0, estimator.sessionPagesRead())
        assertNull(estimator.establishedReadingSpeedWpm.value)

        // New measurements use 100 words per page, not the old 200.
        val measured = page(page = 3, atMs = 90_000, totalPages = 20)
        assertNotNull(measured)
        assertEquals(200, estimator.establishedReadingSpeedWpm.value)
        assertEquals(1, estimator.sessionPagesRead())
    }

    @Test
    fun `reflow cannot report more remaining words than the chapter contains`() {
        page(page = 1, atMs = 0)
        val info = page(page = 1, atMs = 0, totalPages = 15)
        assertNotNull(info)
        assertEquals(1866, info.remainingWords)
        assertTrue(info.remainingWords <= info.totalWords)
    }

    private fun page(
        href: String = "ch1",
        page: Int,
        atMs: Long,
        totalWords: Int = 2000,
        totalPages: Int = 10,
        fallbackWpm: Int = 200,
        trackSpeed: Boolean = true,
    ): com.retro99.reader.ui.model.ChapterReadingTimeInfo? {
        now = atMs
        return estimator.onLocator(
            chapterHref = href,
            progression = page.toDouble() / totalPages,
            chapterInfo = ChapterInfo(
                currentPage = page,
                totalPages = totalPages,
                totalWords = totalWords,
            ),
            fallbackWpm = fallbackWpm,
            trackSpeed = trackSpeed,
        )
    }
}
