package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TTS-F17: a stored `kokoro:<n>` voice id is handed to the native engine as a speaker index,
 * so it has to name a voice that exists. See the finding in `docs/tts-investigation.md`.
 */
class SherpaOnnxSynthesizerTest {

    @Test
    fun `a voice id past the end of the Kokoro list maps to a voice that exists`() {
        // Given / When
        val speakerId = parseKokoroSpeakerId("kokoro:99")

        // Then
        assertTrue(
            speakerId in KOKORO_VOICES.indices,
            "99 must be clamped into 0..${KOKORO_VOICES.lastIndex}, was $speakerId",
        )
    }

    @Test
    fun `a voice id inside the Kokoro list is used as it is`() {
        // Given / When / Then
        assertEquals(5, parseKokoroSpeakerId("kokoro:5"))
    }

    @Test
    fun `a negative, empty, unparseable or absent voice id falls back to the first voice`() {
        // Given / When / Then
        assertEquals(0, parseKokoroSpeakerId("kokoro:-3"))
        assertEquals(0, parseKokoroSpeakerId("kokoro:"))
        assertEquals(0, parseKokoroSpeakerId("kokoro:abc"))
        assertEquals(0, parseKokoroSpeakerId(null))
    }
}
