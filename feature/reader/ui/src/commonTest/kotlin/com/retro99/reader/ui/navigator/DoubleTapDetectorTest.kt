package com.retro99.reader.ui.navigator

import kotlin.test.Test
import kotlin.test.assertTrue

class DoubleTapDetectorTest {

    @Test
    fun `tap detector checks the generated TTS sentence before nested IDs`() {
        // When
        val script = DoubleTapDetector.getTapDetectionScript()

        // Then
        val ttsSentenceLookup = script.indexOf("closest('.parrot-sentence')")
        val genericIdLookup = script.indexOf("while (element && element !== document.body)")
        assertTrue(ttsSentenceLookup >= 0)
        assertTrue(genericIdLookup > ttsSentenceLookup)
    }
}
