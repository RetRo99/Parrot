package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsVoiceTest {

    @Test
    fun `needsDownload reflects whether the voice package is present`() {
        // Given
        val voice = TtsVoice(
            id = "kokoro:0",
            name = "Heart",
            locale = "en",
            isNeural = true,
            downloadSizeBytes = KOKORO_DOWNLOAD_SIZE_BYTES,
            isDownloaded = false,
        )

        // When
        val downloadedVoice = voice.copy(isDownloaded = true)

        // Then
        assertTrue(voice.needsDownload)
        assertFalse(downloadedVoice.needsDownload)
    }

    @Test
    fun `neuralVoicePackage identifies Kokoro and Supertonic voice ids`() {
        // When
        val kokoroPackage = "kokoro:0".neuralVoicePackage()
        val supertonicPackage = "supertonic:0".neuralVoicePackage()
        val systemPackage = "en-us".neuralVoicePackage()

        // Then
        assertTrue(kokoroPackage == NeuralVoicePackage.KOKORO)
        assertTrue(supertonicPackage == NeuralVoicePackage.SUPERTONIC)
        assertTrue(systemPackage == null)
    }

    @Test
    fun `high latency does not make a low quality voice high quality`() {
        // Given
        val lowQualityVoice = TtsVoice(
            id = "slow",
            name = "Slow voice",
            locale = "en",
            quality = 200,
            latency = 500,
        )
        val highQualityVoice = lowQualityVoice.copy(quality = 400, latency = 100)

        // Then
        assertFalse(lowQualityVoice.isHighQuality)
        assertTrue(highQualityVoice.isHighQuality)
    }
}
