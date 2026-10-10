package com.retro99.reader.ui.reader

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How big preparing a chapter will be. Prepared audio is AAC-LC at a steady bit rate, so
 * the size follows from how long the audio is, not from a count of sentences: sentences
 * vary in length, a second of audio does not.
 */
class PreparedChapterSizeEstimateTest {

    private val text = PreparedChapterText("c1.xhtml", sentences = 369, characters = 30_000)

    /** 48 000 bit/s is 6 000 bytes a second; the container costs about 1 050 bytes a file. */
    private fun expected(audioSeconds: Double, files: Int) =
        (audioSeconds * 6_000).toLong() + files * 1_050L

    @Test fun `the size is the audio at the encoder's bit rate plus the container a file`() {
        val estimate = preparedChapterRowEstimate(
            chapterHref = "c1.xhtml",
            text = text,
            voiceKind = PreparedVoiceKind.KOKORO,
            measured = PreparedChapterMeasured(audioMsPerCharacter = 70.0),
            rate = 1f,
        )
        // 30 000 characters at 70 ms is 2 100 s of audio, in 369 files.
        assertEquals(expected(2_100.0, files = 369), assertNotNull(estimate).bytes)
    }

    @Test fun `a faster reading speed makes a shorter and so a smaller chapter`() {
        val estimate = preparedChapterRowEstimate(
            chapterHref = "c1.xhtml",
            text = text,
            voiceKind = PreparedVoiceKind.KOKORO,
            measured = PreparedChapterMeasured(audioMsPerCharacter = 70.0),
            rate = 2f,
        )
        // The rate is applied at synthesis, so the file really is half as long.
        assertEquals(expected(1_050.0, files = 369), assertNotNull(estimate).bytes)
    }

    @Test fun `the nine sentences the Samsung really wrote estimate to about their 219 kB`() {
        // The Samsung's Kokoro chapter: 9 sentences, 17 s to prepare, 219 kB on disk. The
        // old fixed 14 kB a sentence said 130 kB, 40% low.
        val estimate = assertNotNull(preparedChapterEstimate(9, PreparedVoiceKind.KOKORO))
        val actual = 219_000L
        val off = abs(estimate.bytes - actual).toDouble() / actual
        assertTrue(off <= 0.25, "estimated ${estimate.bytes} against $actual, off by $off")
        assertEquals("220 kB", preparedChapterEstimateSizeLabel(estimate.bytes))
    }

    @Test fun `the device's own bytes a sentence stand where it has no audio length yet`() {
        val estimate = preparedChapterEstimate(
            sentenceCount = 9,
            voiceKind = PreparedVoiceKind.KOKORO,
            measured = PreparedChapterMeasured(bytesPerSentence = 24_000),
        )
        assertEquals(9 * 24_000L, assertNotNull(estimate).bytes)
    }

    @Test fun `an audio length this device measured beats a flat bytes a sentence`() {
        // Both are the device's own, but only the audio length knows this chapter's text,
        // and a chapter of long sentences is not a chapter of average ones.
        val estimate = preparedChapterRowEstimate(
            chapterHref = "c1.xhtml",
            text = text,
            voiceKind = PreparedVoiceKind.KOKORO,
            measured = PreparedChapterMeasured(bytesPerSentence = 9_000, audioMsPerCharacter = 70.0),
            rate = 1f,
        )
        assertEquals(expected(2_100.0, files = 369), assertNotNull(estimate).bytes)
    }

    @Test fun `the size and the audio length the row shows tell the same story`() {
        val estimate = assertNotNull(
            preparedChapterRowEstimate(
                chapterHref = "c1.xhtml",
                text = text,
                voiceKind = PreparedVoiceKind.KOKORO,
                measured = PreparedChapterMeasured(audioMsPerCharacter = 70.0),
                rate = 1f,
            ),
        )
        // 35 minutes of audio and 13 MB: 6 000 bytes a second of it, and no other unit.
        assertEquals(35, estimate.audioMinutes)
        assertEquals("13 MB", preparedChapterEstimateSizeLabel(estimate.bytes))
    }
}
