package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsVoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSettingsScreenTest {

    @Test
    fun `toSystemVoiceGroups excludes neural voices and groups system voices by language`() {
        // Given
        val voices = listOf(
            systemVoice(id = "de-de", name = "German (Germany)", locale = "de-DE"),
            systemVoice(id = "en-gb", name = "English (United Kingdom)", locale = "en-GB"),
            systemVoice(id = "en-us", name = "English (United States)", locale = "en-US"),
            TtsVoice(
                id = "kokoro:0",
                name = "Heart (US female)",
                locale = "en",
                isNeural = true,
            ),
        )

        // When
        val result = voices.toSystemVoiceGroups()

        // Then
        assertEquals(listOf("English", "German"), result.map { group -> group.languageLabel })
        assertEquals(listOf("en-gb", "en-us"), result[0].voices.map { voice -> voice.id })
        assertEquals(listOf("de-de"), result[1].voices.map { voice -> voice.id })
    }

    @Test
    fun `isNeuralVoicePackageUpdateAvailable only reports matching packages`() {
        // Given
        val kokoroWithUpdate = TtsVoice(
            id = "kokoro:0",
            name = "Heart (US female)",
            locale = "en",
            isNeural = true,
            isDownloaded = true,
            updateAvailable = true,
        )
        val kokoroCurrent = kokoroWithUpdate.copy(id = "kokoro:1", updateAvailable = false)
        val supertonicWithUpdate = kokoroWithUpdate.copy(id = "supertonic:0", name = "F1")

        // When / Then
        assertTrue(
            listOf(kokoroWithUpdate, kokoroCurrent)
                .isNeuralVoicePackageUpdateAvailable(NeuralVoicePackage.KOKORO),
        )
        assertFalse(
            listOf(kokoroCurrent).isNeuralVoicePackageUpdateAvailable(NeuralVoicePackage.KOKORO),
        )
        assertTrue(
            listOf(kokoroCurrent, supertonicWithUpdate)
                .isNeuralVoicePackageUpdateAvailable(NeuralVoicePackage.SUPERTONIC),
        )
    }

    @Test
    fun `isNeuralVoicePackageDownloaded evaluates each neural package independently`() {
        // Given
        val downloadedNeuralVoice = TtsVoice(
            id = "kokoro:0",
            name = "Heart (US female)",
            locale = "en",
            isNeural = true,
            isDownloaded = true,
        )
        val missingNeuralVoice = downloadedNeuralVoice.copy(isDownloaded = false)
        val downloadedSupertonicVoice = downloadedNeuralVoice.copy(
            id = "supertonic:0",
            name = "F1 (US female)",
        )
        val systemVoice = systemVoice(
            id = "en-us",
            name = "English (United States)",
            locale = "en-US",
        )

        // When
        val downloadedResult = listOf(downloadedNeuralVoice)
            .isNeuralVoicePackageDownloaded(NeuralVoicePackage.KOKORO)
        val missingResult = listOf(missingNeuralVoice)
            .isNeuralVoicePackageDownloaded(NeuralVoicePackage.KOKORO)
        val mixedResult = listOf(
            downloadedNeuralVoice,
            missingNeuralVoice.copy(id = "kokoro:1"),
        ).isNeuralVoicePackageDownloaded(NeuralVoicePackage.KOKORO)
        val independentResult = listOf(
            missingNeuralVoice,
            downloadedSupertonicVoice,
        ).isNeuralVoicePackageDownloaded(NeuralVoicePackage.SUPERTONIC)
        val systemResult = listOf(systemVoice)
            .isNeuralVoicePackageDownloaded(NeuralVoicePackage.KOKORO)

        // Then
        assertTrue(downloadedResult)
        assertFalse(missingResult)
        assertFalse(mixedResult)
        assertTrue(independentResult)
        assertFalse(systemResult)
    }

    @Test
    fun `displayName separates neural voice name from its details`() {
        // Given
        val neuralVoice = TtsVoice(
            id = "kokoro:0",
            name = "Heart (US female)",
            locale = "en",
            isNeural = true,
        )
        val systemVoice = systemVoice(
            id = "en-us",
            name = "English (United States) - High",
            locale = "en-US",
        )

        // When
        val neuralDisplayName = neuralVoice.displayName()
        val systemDisplayName = systemVoice.displayName()

        // Then
        assertEquals("Heart", neuralDisplayName)
        assertEquals("English (United States) - High", systemDisplayName)
    }

    @Test
    fun `neuralVoiceDetails formats region and voice type`() {
        // Given
        val voice = TtsVoice(
            id = "kokoro:0",
            name = "Heart (US female)",
            locale = "en",
            isNeural = true,
        )

        // When
        val result = voice.neuralVoiceDetails()

        // Then
        assertEquals("US · Female", result)
    }

    private fun systemVoice(
        id: String,
        name: String,
        locale: String,
    ): TtsVoice = TtsVoice(
        id = id,
        name = name,
        locale = locale,
    )
}
