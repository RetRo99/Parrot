package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.TtsChapterPreparationFailure
import com.retro99.translations.StringRes
import resources.translations.general_cancel
import resources.translations.general_retry
import resources.translations.reader_tts_delete
import resources.translations.reader_tts_prepared_chapter_continue
import resources.translations.reader_tts_prepared_chapter_failed
import resources.translations.reader_tts_prepared_chapter_failed_space
import resources.translations.reader_tts_prepared_chapter_estimate
import resources.translations.reader_tts_prepared_chapter_estimate_hours
import resources.translations.reader_tts_prepared_chapter_estimate_short
import resources.translations.reader_tts_prepared_chapter_hint
import resources.translations.reader_tts_prepared_chapter_other_busy
import resources.translations.reader_tts_prepared_chapter_other_settings
import resources.translations.reader_tts_prepared_chapter_other_speed
import resources.translations.reader_tts_prepared_chapter_partly
import resources.translations.reader_tts_prepared_chapter_prepare
import resources.translations.reader_tts_prepared_chapter_prepare_again
import resources.translations.reader_tts_prepared_chapter_progress_waiting
import resources.translations.reader_tts_prepared_chapter_ready
import resources.translations.reader_tts_prepared_chapter_voice_pack
import resources.translations.reader_tts_prepared_chapter_voices
import resources.translations.reader_tts_prepared_chapter_voice_terms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every state of the row, with the text it shows and the buttons it offers. */
class PreparedChapterRowUiTest {

    @Test fun `not prepared says what preparing is for and offers one button`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.NotPrepared)
        assertEquals(StringRes.reader_tts_prepared_chapter_hint, ui.status)
        assertEquals(emptyList(), ui.statusArgs)
        assertEquals(listOf(PreparedChapterAction.PREPARE), ui.actions)
        assertEquals(StringRes.reader_tts_prepared_chapter_prepare, ui.actions.single().label)
        assertNull(ui.progress)
    }

    @Test fun `not prepared says the time and the size before the user starts`() {
        val ui = preparedChapterRowUi(
            PreparedChapterRowState.NotPrepared,
            estimate = PreparedChapterEstimate(minutes = 12, bytes = 9_000_000),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_estimate, ui.status)
        assertEquals(listOf(12, "9 MB"), ui.statusArgs)
        assertEquals(listOf(PreparedChapterAction.PREPARE), ui.actions)
    }

    @Test fun `an estimate under a minute says so rather than rounding up to one`() {
        val ui = preparedChapterRowUi(
            PreparedChapterRowState.NotPrepared,
            estimate = PreparedChapterEstimate(minutes = 0, bytes = 120_000),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_estimate_short, ui.status)
        assertEquals(listOf("120 kB"), ui.statusArgs)
    }

    @Test fun `an estimate of an hour or more is said in hours and minutes`() {
        val ui = preparedChapterRowUi(
            PreparedChapterRowState.NotPrepared,
            estimate = PreparedChapterEstimate(minutes = 90, bytes = 60_000_000),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_estimate_hours, ui.status)
        assertEquals(listOf(1, 30, "60 MB"), ui.statusArgs)
    }

    @Test fun `a whole number of hours still reads as hours and zero minutes`() {
        val ui = preparedChapterRowUi(
            PreparedChapterRowState.NotPrepared,
            estimate = PreparedChapterEstimate(minutes = 120, bytes = 1_000_000),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_estimate_hours, ui.status)
        assertEquals(listOf(2, 0, "1 MB"), ui.statusArgs)
    }

    @Test fun `the estimate is only ever shown on the not prepared row`() {
        val estimate = PreparedChapterEstimate(minutes = 12, bytes = 9_000_000)
        val ready = preparedChapterRowUi(PreparedChapterRowState.Ready(81_000), estimate = estimate)
        assertEquals(StringRes.reader_tts_prepared_chapter_ready, ready.status)
        val partly = preparedChapterRowUi(PreparedChapterRowState.Partly(19, 151), estimate = estimate)
        assertEquals(StringRes.reader_tts_prepared_chapter_partly, partly.status)
    }

    @Test fun `preparing counts the sentences with a progress bar and only cancel`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.Preparing(42, 151))
        assertEquals(StringRes.reader_tts_prepared_chapter_progress_waiting, ui.status)
        assertEquals(listOf<Any>(42, 151), ui.statusArgs)
        assertEquals(42f / 151f, ui.progress)
        assertEquals(listOf(PreparedChapterAction.CANCEL), ui.actions)
        assertEquals(StringRes.general_cancel, ui.actions.single().label)
    }

    @Test fun `another chapter preparing says so and offers nothing`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.PreparingAnotherChapter)
        assertEquals(StringRes.reader_tts_prepared_chapter_other_busy, ui.status)
        assertEquals(emptyList(), ui.actions)
    }

    @Test fun `ready shows its size and offers delete`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.Ready(2_450_000))
        assertEquals(StringRes.reader_tts_prepared_chapter_ready, ui.status)
        assertEquals(listOf<Any>("2.5 MB"), ui.statusArgs)
        assertEquals(listOf(PreparedChapterAction.DELETE), ui.actions)
        assertEquals(StringRes.reader_tts_delete, ui.actions.single().label)
        assertTrue(ui.actions.single().isDestructive)
    }

    @Test fun `partly prepared offers continue and delete`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.Partly(42, 151))
        assertEquals(StringRes.reader_tts_prepared_chapter_partly, ui.status)
        assertEquals(listOf<Any>(42, 151), ui.statusArgs)
        assertEquals(
            listOf(PreparedChapterAction.CONTINUE, PreparedChapterAction.DELETE),
            ui.actions,
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_continue, ui.actions.first().label)
    }

    @Test fun `other settings names the voice when it is known and the speed either way`() {
        val named = preparedChapterRowUi(
            PreparedChapterRowState.OtherSettings("kokoro:heart", 1.25f),
            voiceLabel = "Heart",
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_other_settings, named.status)
        assertEquals(listOf<Any>("Heart", "1.25"), named.statusArgs)
        assertEquals(listOf(PreparedChapterAction.PREPARE_AGAIN), named.actions)
        assertEquals(StringRes.reader_tts_prepared_chapter_prepare_again, named.actions.single().label)

        val unnamed = preparedChapterRowUi(PreparedChapterRowState.OtherSettings("gone", 1f))
        assertEquals(StringRes.reader_tts_prepared_chapter_other_speed, unnamed.status)
        assertEquals(listOf<Any>("1"), unnamed.statusArgs)
    }

    @Test fun `a failure shows one line and offers retry`() {
        val failed = preparedChapterRowUi(
            PreparedChapterRowState.Failed(TtsChapterPreparationFailure.SENTENCE_FAILED),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_failed, failed.status)
        assertTrue(failed.isFailure)
        assertEquals(listOf(PreparedChapterAction.RETRY), failed.actions)
        assertEquals(StringRes.general_retry, failed.actions.single().label)

        val noSpace = preparedChapterRowUi(
            PreparedChapterRowState.Failed(TtsChapterPreparationFailure.NOT_ENOUGH_SPACE),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_failed_space, noSpace.status)
    }

    @Test fun `an unusable voice leads to voices and says which gate it is`() {
        val pack = preparedChapterRowUi(PreparedChapterRowState.VoiceUnusable(needsTerms = false))
        assertEquals(StringRes.reader_tts_prepared_chapter_voice_pack, pack.status)
        assertEquals(listOf(PreparedChapterAction.OPEN_VOICES), pack.actions)
        assertEquals(StringRes.reader_tts_prepared_chapter_voices, pack.actions.single().label)

        val terms = preparedChapterRowUi(PreparedChapterRowState.VoiceUnusable(needsTerms = true))
        assertEquals(StringRes.reader_tts_prepared_chapter_voice_terms, terms.status)
    }

    @Test fun `sizes read in megabytes once there is one and in kilobytes below`() {
        assertEquals("2.5 MB", preparedChapterSizeLabel(2_450_000))
        assertEquals("2.4 MB", preparedChapterSizeLabel(2_440_000))
        assertEquals("1.0 MB", preparedChapterSizeLabel(1_000_000))
        assertEquals("940 kB", preparedChapterSizeLabel(940_000))
        assertEquals("1 kB", preparedChapterSizeLabel(1))
        assertEquals("1 kB", preparedChapterSizeLabel(0))
    }

    @Test fun `speeds read without a trailing zero`() {
        assertEquals("1", preparedRateLabel(1f))
        assertEquals("1.1", preparedRateLabel(1.1f))
        assertEquals("1.25", preparedRateLabel(1.25f))
        assertEquals("0.8", preparedRateLabel(0.8f))
        assertEquals("2", preparedRateLabel(2f))
    }
}
