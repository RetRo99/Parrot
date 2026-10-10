package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreparedChapterEstimateTest {

    /** The package is read off the id, so the id is what a test sets. */
    private fun voice(
        isNeural: Boolean,
        pack: NeuralVoicePackage? = null,
    ) = TtsVoice(
        id = when (pack) {
            NeuralVoicePackage.KOKORO -> "kokoro:heart"
            NeuralVoicePackage.SUPERTONIC -> "supertonic:f1"
            null -> "system-voice"
        },
        name = "V",
        locale = "en-US",
        isNeural = isNeural,
    )

    // --- no estimate at all ---------------------------------------------------------

    @Test
    fun `no estimate when the sentence count is not known yet`() {
        assertNull(preparedChapterEstimate(null, PreparedVoiceKind.KOKORO))
    }

    @Test
    fun `no estimate for a zero sentence count`() {
        assertNull(preparedChapterEstimate(0, PreparedVoiceKind.KOKORO))
    }

    @Test
    fun `no estimate for a negative sentence count`() {
        assertNull(preparedChapterEstimate(-3, PreparedVoiceKind.KOKORO))
    }

    // --- the fixed figures ----------------------------------------------------------

    @Test
    fun `kokoro uses its own measured figures`() {
        val estimate = preparedChapterEstimate(100, PreparedVoiceKind.KOKORO)
        // 100 * 2200 ms = 220 s = 3.67 min -> 4. The size is the audio, not the count:
        // 100 * 3900 ms = 390 s at 6 000 B/s, plus 1 050 B a file.
        assertEquals(PreparedChapterEstimate(minutes = 4, bytes = 2_445_000), estimate)
    }

    @Test
    fun `a system voice is slower and larger per sentence than kokoro`() {
        val system = preparedChapterEstimate(100, PreparedVoiceKind.SYSTEM)!!
        val kokoro = preparedChapterEstimate(100, PreparedVoiceKind.KOKORO)!!
        // 100 * 4400 ms = 440 s at 6 000 B/s, plus 1 050 B a file.
        assertEquals(PreparedChapterEstimate(minutes = 4, bytes = 2_745_000), system)
        assertEquals(true, system.bytes > kokoro.bytes)
    }

    @Test
    fun `supertonic takes the kokoro figures being the other neural engine`() {
        assertEquals(
            preparedChapterEstimate(151, PreparedVoiceKind.KOKORO),
            preparedChapterEstimate(151, PreparedVoiceKind.SUPERTONIC),
        )
    }

    @Test
    fun `a 151 sentence chapter with kokoro`() {
        // The chapter size the device checks use. 151 * 2200 ms = 332 s = 5.5 min -> 6.
        val estimate = preparedChapterEstimate(151, PreparedVoiceKind.KOKORO)!!
        assertEquals(6, estimate.minutes)
        // 151 * 3900 ms = 588.9 s at 6 000 B/s, plus 151 * 1 050 B.
        assertEquals(3_691_950L, estimate.bytes)
        assertEquals("4 MB", preparedChapterEstimateSizeLabel(estimate.bytes))
    }

    // --- the device's own measurements win ------------------------------------------

    @Test
    fun `a measured average replaces the fixed figure for this voice`() {
        val estimate = preparedChapterEstimate(
            sentenceCount = 100,
            voiceKind = PreparedVoiceKind.KOKORO,
            measured = PreparedChapterMeasured(msPerSentence = 600, bytesPerSentence = 9_000),
        )
        // 100 * 600 ms = 60 s -> 1 minute; 900 kB.
        assertEquals(PreparedChapterEstimate(minutes = 1, bytes = 900_000), estimate)
    }

    @Test
    fun `one measured figure may be present without the other`() {
        val estimate = preparedChapterEstimate(
            sentenceCount = 100,
            voiceKind = PreparedVoiceKind.KOKORO,
            measured = PreparedChapterMeasured(bytesPerSentence = 20_000),
        )
        assertEquals(4, estimate!!.minutes, "falls back to the fixed time figure")
        assertEquals(2_000_000, estimate.bytes)
    }

    @Test
    fun `a nonsense measured figure is ignored rather than trusted`() {
        val estimate = preparedChapterEstimate(
            sentenceCount = 100,
            voiceKind = PreparedVoiceKind.KOKORO,
            measured = PreparedChapterMeasured(msPerSentence = 0, bytesPerSentence = -1),
        )
        assertEquals(preparedChapterEstimate(100, PreparedVoiceKind.KOKORO), estimate)
    }

    // --- rounding the way a person says it ------------------------------------------

    @Test
    fun `a very short chapter is under a minute`() {
        // 10 * 2200 ms = 22 s.
        assertEquals(0, preparedChapterEstimate(10, PreparedVoiceKind.KOKORO)!!.minutes)
    }

    @Test
    fun `forty five seconds is where a minute starts being said`() {
        val measured = PreparedChapterMeasured(msPerSentence = 1_000)
        assertEquals(0, preparedChapterEstimate(44, PreparedVoiceKind.KOKORO, measured)!!.minutes)
        assertEquals(1, preparedChapterEstimate(45, PreparedVoiceKind.KOKORO, measured)!!.minutes)
    }

    @Test
    fun `single minutes up to ten are said exactly`() {
        val measured = PreparedChapterMeasured(msPerSentence = 60_000)
        for (minutes in 1..9) {
            assertEquals(
                minutes,
                preparedChapterEstimate(minutes, PreparedVoiceKind.KOKORO, measured)!!.minutes,
                "$minutes minutes",
            )
        }
    }

    @Test
    fun `from ten minutes it is rounded to five`() {
        val measured = PreparedChapterMeasured(msPerSentence = 60_000)
        assertEquals(10, preparedChapterEstimate(11, PreparedVoiceKind.KOKORO, measured)!!.minutes)
        assertEquals(15, preparedChapterEstimate(13, PreparedVoiceKind.KOKORO, measured)!!.minutes)
        assertEquals(20, preparedChapterEstimate(21, PreparedVoiceKind.KOKORO, measured)!!.minutes)
    }

    @Test
    fun `from an hour it is rounded to ten`() {
        val measured = PreparedChapterMeasured(msPerSentence = 60_000)
        assertEquals(60, preparedChapterEstimate(62, PreparedVoiceKind.KOKORO, measured)!!.minutes)
        assertEquals(90, preparedChapterEstimate(87, PreparedVoiceKind.KOKORO, measured)!!.minutes)
    }

    // --- the size label -------------------------------------------------------------

    @Test
    fun `the estimate size label is coarser than the exact one`() {
        assertEquals("9 MB", preparedChapterEstimateSizeLabel(9_100_000))
        assertEquals("10 MB", preparedChapterEstimateSizeLabel(9_600_000))
        assertEquals("1 MB", preparedChapterEstimateSizeLabel(1_000_000))
        assertEquals("140 kB", preparedChapterEstimateSizeLabel(140_000))
        assertEquals("10 kB", preparedChapterEstimateSizeLabel(1))
    }

    // --- which kind a voice is ------------------------------------------------------

    @Test
    fun `no voice at all counts as the system voice`() {
        assertEquals(PreparedVoiceKind.SYSTEM, preparedVoiceKind(null))
    }

    @Test
    fun `a non neural voice is the system voice`() {
        assertEquals(PreparedVoiceKind.SYSTEM, preparedVoiceKind(voice(isNeural = false)))
    }

    @Test
    fun `a supertonic voice is recognised`() {
        assertEquals(
            PreparedVoiceKind.SUPERTONIC,
            preparedVoiceKind(voice(isNeural = true, pack = NeuralVoicePackage.SUPERTONIC)),
        )
    }

    @Test
    fun `any other neural voice is kokoro`() {
        assertEquals(
            PreparedVoiceKind.KOKORO,
            preparedVoiceKind(voice(isNeural = true, pack = NeuralVoicePackage.KOKORO)),
        )
    }
}
