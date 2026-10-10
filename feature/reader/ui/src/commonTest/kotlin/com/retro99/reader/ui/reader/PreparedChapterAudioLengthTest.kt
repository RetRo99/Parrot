package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.TtsPreparationSample
import com.retro99.reader.ui.tts.TtsPreparationSpeedRecord
import com.retro99.reader.ui.tts.decodePreparationSpeedRecord
import com.retro99.reader.ui.tts.encodePreparationSpeedRecord
import com.retro99.translations.StringRes
import resources.translations.reader_tts_prepared_chapter_audio_length
import resources.translations.reader_tts_prepared_chapter_audio_length_hours
import resources.translations.reader_tts_prepared_chapter_audio_length_short
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Roughly how long the chapter is to listen to, said before it is prepared. */
class PreparedChapterAudioLengthTest {

    private val text = PreparedChapterText("c1.xhtml", sentences = 369, characters = 30_000)

    private fun record(voiceId: String?, count: Int, audioMs: Long) = List(count) {
        TtsPreparationSample(workMs = 3_000, characters = 100, audioMs = audioMs)
    }.fold(TtsPreparationSpeedRecord()) { record, sample -> record.with(voiceId, sample) }

    @Test fun `the record gives the audio length per character of a voice`() {
        val record = record("voice-a", count = 3, audioMs = 7_000)
        assertEquals(70.0, assertNotNull(record.audioMsPerCharacter("voice-a")), 0.001)
        assertNull(record.audioMsPerCharacter("voice-b"))
    }

    @Test fun `measurements without an audio length are not an audio length`() {
        assertNull(record("voice-a", count = 5, audioMs = 0).audioMsPerCharacter("voice-a"))
        val two = record("voice-a", count = 2, audioMs = 7_000)
        assertNull(two.audioMsPerCharacter("voice-a"))
    }

    @Test fun `the audio length survives being written and read`() {
        val read = decodePreparationSpeedRecord(encodePreparationSpeedRecord(record("voice-a", 3, 7_000)))
        assertEquals(70.0, assertNotNull(read.audioMsPerCharacter("voice-a")), 0.001)
    }

    @Test fun `without a record the audio length comes from the fixed spoken length per sentence`() {
        // 369 sentences at 3.9 s is 24 minutes, said as 25.
        assertEquals(25, preparedChapterAudioMinutes(369, 30_000, PreparedVoiceKind.SUPERTONIC))
        assertEquals(25, preparedChapterAudioMinutes(369, null, PreparedVoiceKind.SUPERTONIC))
    }

    @Test fun `the device's own audio length for this voice replaces the fixed figure`() {
        val measured = PreparedChapterMeasured(audioMsPerCharacter = 70.0)
        // 30 000 characters at 70 ms is 35 minutes.
        assertEquals(35, preparedChapterAudioMinutes(369, 30_000, PreparedVoiceKind.SUPERTONIC, measured))
    }

    @Test fun `a faster reading speed makes the chapter shorter to listen to`() {
        val measured = PreparedChapterMeasured(audioMsPerCharacter = 70.0)
        // 35 minutes at 1.25x is 28, said as 30; at 2x it is 17.5, said as 20.
        assertEquals(30, preparedChapterAudioMinutes(369, 30_000, PreparedVoiceKind.SUPERTONIC, measured, rate = 1.25f))
        assertEquals(20, preparedChapterAudioMinutes(369, 30_000, PreparedVoiceKind.SUPERTONIC, measured, rate = 2f))
    }

    @Test fun `no sentence count means no audio length`() {
        assertNull(preparedChapterAudioMinutes(null, null, PreparedVoiceKind.SYSTEM))
        assertNull(preparedChapterAudioMinutes(0, 0, PreparedVoiceKind.SYSTEM))
    }

    @Test fun `the row's estimate carries the audio length of the chapter on screen`() {
        val estimate = preparedChapterRowEstimate(
            chapterHref = "c1.xhtml",
            text = text,
            voiceKind = PreparedVoiceKind.SUPERTONIC,
            measured = PreparedChapterMeasured(audioMsPerCharacter = 70.0),
            rate = 1f,
        )
        assertEquals(35, assertNotNull(estimate).audioMinutes)
    }

    @Test fun `the not prepared row says the audio length on its own line`() {
        val ui = preparedChapterRowUi(
            PreparedChapterRowState.NotPrepared,
            estimate = PreparedChapterEstimate(minutes = 20, bytes = 5_000_000, audioMinutes = 35),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_audio_length, ui.detail)
        assertEquals(listOf(35), ui.detailArgs)
    }

    @Test fun `a short and a long chapter say their audio length as the estimate does`() {
        val short = preparedChapterRowUi(
            PreparedChapterRowState.NotPrepared,
            estimate = PreparedChapterEstimate(minutes = 0, bytes = 100_000, audioMinutes = 0),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_audio_length_short, short.detail)
        assertEquals(emptyList(), short.detailArgs)
        val long = preparedChapterRowUi(
            PreparedChapterRowState.NotPrepared,
            estimate = PreparedChapterEstimate(minutes = 60, bytes = 30_000_000, audioMinutes = 90),
        )
        assertEquals(StringRes.reader_tts_prepared_chapter_audio_length_hours, long.detail)
        assertEquals(listOf(1, 30), long.detailArgs)
    }

    @Test fun `no estimate or no audio length means no second line`() {
        assertNull(preparedChapterRowUi(PreparedChapterRowState.NotPrepared).detail)
        assertNull(
            preparedChapterRowUi(
                PreparedChapterRowState.NotPrepared,
                estimate = PreparedChapterEstimate(minutes = 12, bytes = 9_000_000),
            ).detail,
        )
        assertNull(preparedChapterRowUi(PreparedChapterRowState.Preparing(1, 3)).detail)
    }
}
