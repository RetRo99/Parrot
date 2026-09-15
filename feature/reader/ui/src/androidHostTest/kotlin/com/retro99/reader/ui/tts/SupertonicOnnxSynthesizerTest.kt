package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class SupertonicOnnxSynthesizerTest {

    @Test
    fun `Supertonic speaker IDs match packed voice order`() {
        // Given
        val expectedVoices = listOf(
            "supertonic:0" to "F1 (female)",
            "supertonic:1" to "F2 (female)",
            "supertonic:2" to "F3 (female)",
            "supertonic:3" to "F4 (female)",
            "supertonic:4" to "F5 (female)",
            "supertonic:5" to "M1 (male)",
            "supertonic:6" to "M2 (male)",
            "supertonic:7" to "M3 (male)",
            "supertonic:8" to "M4 (male)",
            "supertonic:9" to "M5 (male)",
        )

        // When
        val actualVoices = SUPERTONIC_VOICES.map { voice -> voice.id to voice.name }

        // Then
        assertEquals(expectedVoices, actualVoices)
    }
}
