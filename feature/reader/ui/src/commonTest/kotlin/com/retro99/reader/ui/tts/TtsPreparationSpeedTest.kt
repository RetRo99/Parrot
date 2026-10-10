package com.retro99.reader.ui.tts

import com.retro99.reader.ui.reader.PreparedChapterMeasured
import com.retro99.reader.ui.reader.PreparedChapterText
import com.retro99.reader.ui.reader.PreparedVoiceKind
import com.retro99.reader.ui.reader.preparedChapterEstimate
import com.retro99.reader.ui.reader.preparedChapterRowEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How fast this voice really is on this device: the record and what the estimate does with it. */
class TtsPreparationSpeedTest {

    private fun steady(count: Int, msPerCharacter: Long = 30, characters: Int = 100) =
        List(count) { TtsPreparationSample(workMs = msPerCharacter * characters, characters = characters) }

    private fun record(voiceId: String?, samples: List<TtsPreparationSample>) =
        samples.fold(TtsPreparationSpeedRecord()) { record, sample -> record.with(voiceId, sample) }

    @Test fun `the speed is the work time over the characters it produced`() {
        val samples = listOf(
            TtsPreparationSample(3_000, 100),
            TtsPreparationSample(6_000, 200),
            TtsPreparationSample(1_500, 50),
        )
        assertEquals(30.0, assertNotNull(preparationMsPerCharacter(samples)), 0.001)
    }

    @Test fun `fewer than three measurements are not a speed yet`() {
        assertNull(preparationMsPerCharacter(steady(2)))
        assertNull(preparationMsPerCharacter(emptyList()))
    }

    @Test fun `measurements with no time or no text are not counted`() {
        val samples = steady(2) + TtsPreparationSample(0, 100) + TtsPreparationSample(500, 0) +
            TtsPreparationSample(-5, 10)
        assertNull(preparationMsPerCharacter(samples))
    }

    @Test fun `one very slow sentence does not throw the speed off`() {
        val samples = steady(10) + TtsPreparationSample(workMs = 60_000, characters = 100)
        val speed = assertNotNull(preparationMsPerCharacter(samples))
        assertTrue(speed in 30.0..36.0, "one sentence twenty times slower moved the speed to $speed")
    }

    @Test fun `a record updates with new measurements`() {
        val slow = record("voice-a", steady(3, msPerCharacter = 30))
        assertEquals(30.0, assertNotNull(slow.msPerCharacter("voice-a")), 0.001)
        val mixed = steady(3, msPerCharacter = 10).fold(slow) { record, sample -> record.with("voice-a", sample) }
        assertEquals(20.0, assertNotNull(mixed.msPerCharacter("voice-a")), 0.001)
    }

    @Test fun `a record keeps only the newest measurements of a voice`() {
        val old = record("voice-a", steady(PREPARATION_MAX_SAMPLES_PER_VOICE, msPerCharacter = 30))
        val replaced = steady(PREPARATION_MAX_SAMPLES_PER_VOICE, msPerCharacter = 10)
            .fold(old) { record, sample -> record.with("voice-a", sample) }
        assertEquals(PREPARATION_MAX_SAMPLES_PER_VOICE, replaced.samplesByVoice.values.single().size)
        assertEquals(10.0, assertNotNull(replaced.msPerCharacter("voice-a")), 0.001)
    }

    @Test fun `a different voice does not use another voice's record`() {
        val record = record("voice-a", steady(5))
        assertNull(record.msPerCharacter("voice-b"))
        assertNull(record.msPerCharacter(null))
        val both = steady(3, msPerCharacter = 80).fold(record) { r, sample -> r.with(null, sample) }
        assertEquals(80.0, assertNotNull(both.msPerCharacter(null)), 0.001)
        assertEquals(30.0, assertNotNull(both.msPerCharacter("voice-a")), 0.001)
    }

    @Test fun `a record survives being written and read`() {
        val record = steady(3, msPerCharacter = 80).fold(record("voice a/1", steady(4))) { r, s -> r.with(null, s) }
        val read = decodePreparationSpeedRecord(encodePreparationSpeedRecord(record))
        assertEquals(record, read)
        assertEquals(30.0, assertNotNull(read.msPerCharacter("voice a/1")), 0.001)
    }

    @Test fun `an absent or unreadable record is an empty one`() {
        assertEquals(TtsPreparationSpeedRecord(), decodePreparationSpeedRecord(null))
        assertEquals(TtsPreparationSpeedRecord(), decodePreparationSpeedRecord(""))
        assertEquals(TtsPreparationSpeedRecord(), decodePreparationSpeedRecord("{\"not\": \"ours\"}\n\u0000\u0001"))
        assertNull(decodePreparationSpeedRecord("x y z\n-1 -1 voice").msPerCharacter("voice"))
    }

    @Test fun `a damaged line does not lose the good ones`() {
        val good = encodePreparationSpeedRecord(record("voice-a", steady(3)))
        val read = decodePreparationSpeedRecord("garbage\n$good\n12 notanumber voice-a")
        assertEquals(30.0, assertNotNull(read.msPerCharacter("voice-a")), 0.001)
    }

    @Test fun `the device's own speed for this voice replaces the fixed figure`() {
        val estimate = preparedChapterEstimate(
            sentenceCount = 369,
            voiceKind = PreparedVoiceKind.SUPERTONIC,
            measured = PreparedChapterMeasured(msPerCharacter = 36.0),
            characterCount = 30_000,
        )
        // 30 000 characters at 36 ms is 18 minutes, said as 20; the fixed figure says 15.
        assertEquals(20, assertNotNull(estimate).minutes)
    }

    @Test fun `without a record the fixed figures are used`() {
        val estimate = preparedChapterEstimate(
            sentenceCount = 369,
            voiceKind = PreparedVoiceKind.SUPERTONIC,
            measured = PreparedChapterMeasured(),
            characterCount = 30_000,
        )
        assertEquals(15, assertNotNull(estimate).minutes)
    }

    @Test fun `a speed without a character count cannot be used and the fixed figure stays`() {
        val estimate = preparedChapterEstimate(
            sentenceCount = 369,
            voiceKind = PreparedVoiceKind.SUPERTONIC,
            measured = PreparedChapterMeasured(msPerCharacter = 36.0),
        )
        assertEquals(15, assertNotNull(estimate).minutes)
    }

    @Test fun `the row's estimate uses the chapter's characters with the device's speed`() {
        val estimate = preparedChapterRowEstimate(
            chapterHref = "c1.xhtml",
            text = PreparedChapterText("c1.xhtml", sentences = 369, characters = 30_000),
            voiceKind = PreparedVoiceKind.SUPERTONIC,
            measured = PreparedChapterMeasured(msPerCharacter = 36.0, bytesPerSentence = 20_000),
        )
        assertEquals(20, assertNotNull(estimate).minutes)
        assertEquals(369 * 20_000L, estimate.bytes)
    }
}
