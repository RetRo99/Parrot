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
            isDownloaded = false,
        )

        // When
        val downloadedVoice = voice.copy(isDownloaded = true)

        // Then
        assertTrue(voice.needsDownload)
        assertFalse(downloadedVoice.needsDownload)
    }

    @Test
    fun `needsDownload is independent of whether the download size is known`() {
        // Given
        val voiceWithKnownSize = TtsVoice(
            id = "supertonic:0",
            name = "F1",
            locale = "en",
            isNeural = true,
            downloadSizeBytes = 145_000_000L,
            isDownloaded = false,
        )

        // When
        val voiceWithUnknownSize = voiceWithKnownSize.copy(downloadSizeBytes = null)

        // Then
        assertTrue(voiceWithKnownSize.needsDownload)
        assertTrue(voiceWithUnknownSize.needsDownload)
    }

    @Test
    fun `system voices never need download`() {
        // Given
        val systemVoice = TtsVoice(
            id = "en-us",
            name = "System voice",
            locale = "en",
            isNeural = false,
        )

        // Then
        assertFalse(systemVoice.needsDownload)
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
