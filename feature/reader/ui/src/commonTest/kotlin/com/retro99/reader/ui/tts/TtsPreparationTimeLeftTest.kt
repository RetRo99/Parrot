package com.retro99.reader.ui.tts

import com.retro99.reader.ui.reader.PreparedChapterRowState
import com.retro99.reader.ui.reader.PreparedChapterVoice
import com.retro99.reader.ui.reader.PreparedTimeLeftLabel
import com.retro99.reader.ui.reader.derivePreparedChapterRow
import com.retro99.reader.ui.reader.preparedChapterRowUi
import com.retro99.reader.ui.reader.preparedTimeLeftLabel
import com.retro99.translations.StringRes
import resources.translations.reader_tts_prepared_chapter_progress
import resources.translations.reader_tts_prepared_chapter_progress_left
import resources.translations.reader_tts_prepared_chapter_progress_left_hours
import resources.translations.reader_tts_prepared_chapter_progress_left_short
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How long is left while a chapter is being prepared: the arithmetic, the steadiness, the words. */
class TtsPreparationTimeLeftTest {

    private fun steady(count: Int) = List(count) { TtsPreparationSample(workMs = 3_000, characters = 100) }
    private val minute = 60_000L

    @Test fun `the time left is the characters still to do at this run's speed`() {
        assertEquals(300_000L, preparationRemainingMs(steady(3), remainingCharacters = 10_000))
        assertEquals(0L, preparationRemainingMs(steady(3), remainingCharacters = 0))
    }

    @Test fun `there is no time left to say before three sentences have been generated`() {
        assertNull(preparationRemainingMs(emptyList(), remainingCharacters = 10_000))
        assertNull(preparationRemainingMs(steady(2), remainingCharacters = 10_000))
    }

    @Test fun `the first estimate is shown as it is`() {
        assertEquals(
            TtsPreparationTimeLeft(remainingMs = 14 * minute, shownAtMs = 1_000),
            nextPreparationTimeLeft(shown = null, estimateMs = 14 * minute, nowMs = 1_000),
        )
        assertNull(nextPreparationTimeLeft(shown = null, estimateMs = null, nowMs = 1_000))
    }

    @Test fun `a new value is not shown more often than every five seconds`() {
        val shown = TtsPreparationTimeLeft(remainingMs = 14 * minute, shownAtMs = 10_000)
        assertEquals(shown, nextPreparationTimeLeft(shown, estimateMs = 13 * minute, nowMs = 14_999))
        assertEquals(
            TtsPreparationTimeLeft(remainingMs = 13 * minute, shownAtMs = 15_000),
            nextPreparationTimeLeft(shown, estimateMs = 13 * minute, nowMs = 15_000),
        )
    }

    @Test fun `a slightly longer estimate does not make the time left go up`() {
        val shown = TtsPreparationTimeLeft(remainingMs = 10 * minute, shownAtMs = 0)
        assertEquals(shown, nextPreparationTimeLeft(shown, estimateMs = 11 * minute, nowMs = 60_000))
        assertEquals(shown, nextPreparationTimeLeft(shown, estimateMs = 12 * minute, nowMs = 60_000))
    }

    @Test fun `an estimate that was wrong by a lot is corrected upwards`() {
        val shown = TtsPreparationTimeLeft(remainingMs = 10 * minute, shownAtMs = 0)
        assertEquals(
            TtsPreparationTimeLeft(remainingMs = 20 * minute, shownAtMs = 60_000),
            nextPreparationTimeLeft(shown, estimateMs = 20 * minute, nowMs = 60_000),
        )
        // Near the end a few seconds more is a large share but not a large mistake.
        val nearEnd = TtsPreparationTimeLeft(remainingMs = 10_000, shownAtMs = 0)
        assertEquals(nearEnd, nextPreparationTimeLeft(nearEnd, estimateMs = 25_000, nowMs = 60_000))
    }

    @Test fun `a missing estimate keeps what is shown`() {
        val shown = TtsPreparationTimeLeft(remainingMs = 10 * minute, shownAtMs = 0)
        assertEquals(shown, nextPreparationTimeLeft(shown, estimateMs = null, nowMs = 60_000))
    }

    @Test fun `the time left is rounded as the estimate before the press is`() {
        assertNull(preparedTimeLeftLabel(null))
        assertEquals(PreparedTimeLeftLabel.UnderMinute, preparedTimeLeftLabel(0))
        assertEquals(PreparedTimeLeftLabel.UnderMinute, preparedTimeLeftLabel(44_000))
        assertEquals(PreparedTimeLeftLabel.Minutes(1), preparedTimeLeftLabel(45_000))
        assertEquals(PreparedTimeLeftLabel.Minutes(7), preparedTimeLeftLabel(7 * minute))
        assertEquals(PreparedTimeLeftLabel.Minutes(15), preparedTimeLeftLabel(14 * minute))
        assertEquals(PreparedTimeLeftLabel.Hours(1, 30), preparedTimeLeftLabel(88 * minute))
    }

    @Test fun `the running state's time left reaches the row`() {
        val row = derivePreparedChapterRow(
            isReadAloudAvailable = true,
            isNarrationSelected = false,
            chapterHref = "c1.xhtml",
            audio = TtsPreparedChapterAudio.NotPrepared,
            preparation = TtsChapterPreparationState.Running("c1.xhtml", 42, 369, remainingMs = 14 * minute),
            voice = PreparedChapterVoice.USABLE,
        )
        assertEquals(PreparedChapterRowState.Preparing(42, 369, remainingMs = 14 * minute), row)
    }

    @Test fun `while preparing the row says the count and how long is left`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.Preparing(42, 369, remainingMs = 7 * minute))
        assertEquals(StringRes.reader_tts_prepared_chapter_progress_left, ui.status)
        assertEquals(listOf(42, 369, 7), ui.statusArgs)
        assertEquals(42f / 369f, ui.progress)
    }

    @Test fun `the last stretch and a very long chapter are said in their own words`() {
        val short = preparedChapterRowUi(PreparedChapterRowState.Preparing(360, 369, remainingMs = 20_000))
        assertEquals(StringRes.reader_tts_prepared_chapter_progress_left_short, short.status)
        assertEquals(listOf(360, 369), short.statusArgs)
        val long = preparedChapterRowUi(PreparedChapterRowState.Preparing(5, 2_000, remainingMs = 88 * minute))
        assertEquals(StringRes.reader_tts_prepared_chapter_progress_left_hours, long.status)
        assertEquals(listOf(5, 2_000, 1, 30), long.statusArgs)
    }

    @Test fun `before there is an estimate the row says only the count`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.Preparing(2, 369))
        assertEquals(StringRes.reader_tts_prepared_chapter_progress, ui.status)
        assertEquals(listOf(2, 369), ui.statusArgs)
    }
}
