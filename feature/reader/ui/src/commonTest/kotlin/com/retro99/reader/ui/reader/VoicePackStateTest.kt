package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoicePackStateTest {

    @Test
    fun `not downloaded kokoro pack shows its size`() {
        // Given
        val voices = kokoroVoices(isDownloaded = false, sizeBytes = 250_000_000L)

        // When
        val state = state(NeuralVoicePackage.KOKORO, voices)

        // Then
        assertEquals(PackUiState.NotDownloaded(sizeMb = 250), state)
        assertFalse(state.areVoicesSelectable)
    }

    @Test
    fun `supertonic without accepted terms requires terms`() {
        // Given
        val voices = supertonicVoices(isDownloaded = false)

        // When
        val state = state(NeuralVoicePackage.SUPERTONIC, voices, hasAcceptedTerms = false)

        // Then
        assertIs<PackUiState.TermsRequired>(state)
    }

    @Test
    fun `supertonic with accepted terms is plainly not downloaded`() {
        // Given
        val voices = supertonicVoices(isDownloaded = false)

        // When
        val state = state(NeuralVoicePackage.SUPERTONIC, voices, hasAcceptedTerms = true)

        // Then
        assertIs<PackUiState.NotDownloaded>(state)
    }

    @Test
    fun `downloading pack reports percent and megabytes`() {
        // Given
        val voices = kokoroVoices(isDownloaded = false, sizeBytes = 250_000_000L)
        val progress = TtsPreparationProgress.Downloading(
            downloadedBytes = 112_500_000L,
            totalBytes = 250_000_000L,
        )

        // When
        val state = state(
            NeuralVoicePackage.KOKORO,
            voices,
            preparing = NeuralVoicePackage.KOKORO,
            progress = progress,
        )

        // Then
        assertEquals(PackUiState.Downloading(percent = 45, downloadedMb = 113, totalMb = 250), state)
    }

    @Test
    fun `finalizing progress maps to finishing`() {
        // Given
        val voices = kokoroVoices(isDownloaded = false)

        // When
        val state = state(
            NeuralVoicePackage.KOKORO,
            voices,
            preparing = NeuralVoicePackage.KOKORO,
            progress = TtsPreparationProgress.Finalizing,
        )

        // Then
        assertEquals(PackUiState.Finishing, state)
    }

    @Test
    fun `downloaded pack has selectable voices`() {
        // Given
        val voices = kokoroVoices(isDownloaded = true)

        // When
        val state = state(NeuralVoicePackage.KOKORO, voices)

        // Then
        assertEquals(PackUiState.Downloaded, state)
        assertTrue(state.areVoicesSelectable)
    }

    @Test
    fun `update available keeps voices selectable and shows update size`() {
        // Given
        val voices = kokoroVoices(isDownloaded = true, updateAvailable = true, updateSizeBytes = 18_000_000L)

        // When
        val state = state(NeuralVoicePackage.KOKORO, voices)

        // Then
        assertEquals(PackUiState.UpdateAvailable(updateSizeMb = 18), state)
        assertTrue(state.areVoicesSelectable)
    }

    @Test
    fun `failed pack makes voices unavailable`() {
        // Given
        val voices = kokoroVoices(isDownloaded = false)

        // When
        val state = state(NeuralVoicePackage.KOKORO, voices, failed = NeuralVoicePackage.KOKORO)

        // Then
        assertEquals(PackUiState.Failed, state)
        assertFalse(state.areVoicesSelectable)
    }

    @Test
    fun `deleting wins over every other state`() {
        // Given
        val voices = kokoroVoices(isDownloaded = true)

        // When
        val state = state(
            NeuralVoicePackage.KOKORO,
            voices,
            failed = NeuralVoicePackage.KOKORO,
            deleting = NeuralVoicePackage.KOKORO,
        )

        // Then
        assertEquals(PackUiState.Deleting, state)
    }

    @Test
    fun `state of another pack does not leak into this pack`() {
        // Given
        val voices = kokoroVoices(isDownloaded = true)

        // When
        val state = state(
            NeuralVoicePackage.KOKORO,
            voices,
            preparing = NeuralVoicePackage.SUPERTONIC,
            deleting = NeuralVoicePackage.SUPERTONIC,
        )

        // Then
        assertEquals(PackUiState.Downloaded, state)
    }

    @Test
    fun `system voices are grouped by region and numbered within it`() {
        // Given
        val voices = listOf(
            systemVoice("en-us-b", "United States"),
            systemVoice("en-gb-a", "United Kingdom"),
            systemVoice("en-us-a", "United States"),
            systemVoice("en-xx-a", ""),
        )

        // When
        val groups = voices.toRegionGroups()

        // Then
        assertEquals(listOf("United Kingdom", "United States", ""), groups.map { group -> group.regionLabel })
        assertEquals(
            listOf("en-us-a" to 1, "en-us-b" to 2),
            groups[1].voices.map { row -> row.voice.id to row.number },
        )
    }

    @Test
    fun `default language prefers the book language when it has voices`() {
        // When
        val result = defaultVoiceLanguage(setOf("de", "en", "sq"), bookLanguage = "sq-AL", phoneLanguage = "de")

        // Then
        assertEquals("sq", result)
    }

    @Test
    fun `default language falls back to the phone language then english`() {
        // When
        val phone = defaultVoiceLanguage(setOf("de", "en"), bookLanguage = "sq", phoneLanguage = "de")
        val english = defaultVoiceLanguage(setOf("en", "sq"), bookLanguage = null, phoneLanguage = "fr")

        // Then
        assertEquals("de", phone)
        assertEquals("en", english)
    }

    @Test
    fun `default language is never the first alphabetical one`() {
        // When
        val result = defaultVoiceLanguage(setOf("sq", "zh"), bookLanguage = null, phoneLanguage = null)

        // Then
        assertEquals("en", result)
    }

    private fun state(
        pack: NeuralVoicePackage,
        voices: List<TtsVoice>,
        preparing: NeuralVoicePackage? = null,
        progress: TtsPreparationProgress? = null,
        failed: NeuralVoicePackage? = null,
        deleting: NeuralVoicePackage? = null,
        hasAcceptedTerms: Boolean = true,
    ): PackUiState = derivePackState(
        voicePackage = pack,
        voices = voices,
        preparingPackage = preparing,
        progress = progress,
        failedPackage = failed,
        deletingPackage = deleting,
        hasAcceptedTerms = hasAcceptedTerms,
    )

    private fun kokoroVoices(
        isDownloaded: Boolean,
        sizeBytes: Long? = null,
        updateAvailable: Boolean = false,
        updateSizeBytes: Long? = null,
    ): List<TtsVoice> = (0..2).map { index ->
        TtsVoice(
            id = "kokoro:$index",
            name = "Voice $index",
            locale = "en",
            isNeural = true,
            isDownloaded = isDownloaded,
            downloadSizeBytes = sizeBytes,
            updateAvailable = updateAvailable,
            updateSizeBytes = updateSizeBytes,
        )
    }

    private fun supertonicVoices(isDownloaded: Boolean): List<TtsVoice> = (0..1).map { index ->
        TtsVoice(
            id = "supertonic:$index",
            name = "F$index",
            locale = "en",
            isNeural = true,
            isDownloaded = isDownloaded,
        )
    }

    private fun systemVoice(id: String, region: String): TtsVoice = TtsVoice(
        id = id,
        name = id,
        locale = "en",
        regionLabel = region,
    )
}
