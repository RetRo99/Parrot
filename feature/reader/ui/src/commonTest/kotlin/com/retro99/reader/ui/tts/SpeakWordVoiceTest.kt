package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpeakWordVoiceTest {

    private val selectedSystem = systemVoice(id = "en-us-local", name = "English (US) local")
    private val defaultSystem = systemVoice(id = "en-gb-default", name = "English (UK) default")
    private val slowSystem = systemVoice(
        id = "en-us-slow",
        name = "English (US) slow",
        quality = 200,
        latency = 300,
    )
    private val slovenianSystem = systemVoice(id = "sl-si-local", name = "Slovenian", locale = "sl-SI")
    private val kokoro = neuralVoice(id = "kokoro:0", name = "Kokoro Amy", NeuralVoicePackage.KOKORO)
    private val supertonic = neuralVoice(
        id = "supertonic:0",
        name = "Supertonic F1",
        NeuralVoicePackage.SUPERTONIC,
    )

    // Given / When / Then style, one scenario per rule branch.

    @Test
    fun `selected english system voice wins over downloaded neural voice`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = selectedSystem.id,
            voices = listOf(kokoro, supertonic, selectedSystem),
            defaultSystemVoiceId = defaultSystem.id,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(selectedSystem.id, selectedSystem.name, isNeural = false),
            resolution,
        )
    }

    @Test
    fun `selected downloaded neural voice with terms accepted is usable`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = supertonic.id,
            voices = listOf(supertonic, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(supertonic.id, supertonic.name, isNeural = true),
            resolution,
        )
    }

    @Test
    fun `selected downloaded kokoro voice is usable regardless of supertonic terms`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = kokoro.id,
            voices = listOf(kokoro, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = false,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(kokoro.id, kokoro.name, isNeural = true),
            resolution,
        )
    }

    @Test
    fun `update available but installed neural voice stays usable`() {
        val updated = kokoro.copy(updateAvailable = true)
        val resolution = resolveWordVoice(
            selectedVoiceId = updated.id,
            voices = listOf(updated),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(updated.id, updated.name, isNeural = true),
            resolution,
        )
    }

    @Test
    fun `not downloaded neural voice falls back and is never returned`() {
        val notDownloaded = kokoro.copy(isDownloaded = false)
        val resolution = resolveWordVoice(
            selectedVoiceId = notDownloaded.id,
            voices = listOf(notDownloaded, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(selectedSystem.id, selectedSystem.name, isNeural = false),
            resolution,
        )
    }

    @Test
    fun `neural voice with download or preparation running falls back`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = supertonic.id,
            voices = listOf(supertonic, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = NeuralVoicePackage.SUPERTONIC,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(selectedSystem.id, selectedSystem.name, isNeural = false),
            resolution,
        )
    }

    @Test
    fun `preparation of a different package does not disqualify the selected voice`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = supertonic.id,
            voices = listOf(supertonic, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = NeuralVoicePackage.KOKORO,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(supertonic.id, supertonic.name, isNeural = true),
            resolution,
        )
    }

    @Test
    fun `supertonic without accepted terms falls back to a system voice`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = supertonic.id,
            voices = listOf(supertonic, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = false,
            preparingPackage = null,
            language = "en",
        )
        assertTrue(resolution is WordVoiceResolution.Usable)
        assertNotEquals(supertonic.id, resolution.voiceId)
        assertEquals(false, resolution.isNeural)
    }

    @Test
    fun `selected non-english system voice falls back to an english one`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = slovenianSystem.id,
            voices = listOf(slovenianSystem, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(selectedSystem.id, selectedSystem.name, isNeural = false),
            resolution,
        )
    }

    @Test
    fun `unknown selected voice id falls back to the default system voice`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = "gone-with-the-engine",
            voices = listOf(selectedSystem, defaultSystem),
            defaultSystemVoiceId = defaultSystem.id,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(defaultSystem.id, defaultSystem.name, isNeural = false),
            resolution,
        )
    }

    @Test
    fun `null selection resolves to the default system voice when it is english`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = null,
            voices = listOf(selectedSystem, defaultSystem),
            defaultSystemVoiceId = defaultSystem.id,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(defaultSystem.id, defaultSystem.name, isNeural = false),
            resolution,
        )
    }

    @Test
    fun `fallback skips a non-english default and picks the best ranked english voice`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = supertonic.id,
            voices = listOf(supertonic, slovenianSystem, slowSystem, selectedSystem),
            defaultSystemVoiceId = slovenianSystem.id,
            hasAcceptedSupertonicTerms = false,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(
            WordVoiceResolution.Usable(selectedSystem.id, selectedSystem.name, isNeural = false),
            resolution,
        )
    }

    @Test
    fun `fallback ranks quality first then latency`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = supertonic.id,
            voices = listOf(supertonic, slowSystem, selectedSystem),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = false,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(selectedSystem.id, (resolution as WordVoiceResolution.Usable).voiceId)
    }

    @Test
    fun `network-only voices are never chosen`() {
        val networkVoice = selectedSystem.copy(id = "en-us-cloud", requiresNetwork = true)
        val resolution = resolveWordVoice(
            selectedVoiceId = networkVoice.id,
            voices = listOf(networkVoice),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "en",
        )
        assertEquals(WordVoiceResolution.Hidden, resolution)
    }

    @Test
    fun `no voice for the word language hides the button`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = selectedSystem.id,
            voices = listOf(selectedSystem, kokoro),
            defaultSystemVoiceId = selectedSystem.id,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "sl",
        )
        assertEquals(WordVoiceResolution.Hidden, resolution)
    }

    @Test
    fun `non-english language hides even when the selected voice is usable`() {
        val resolution = resolveWordVoice(
            selectedVoiceId = supertonic.id,
            voices = listOf(supertonic),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = true,
            preparingPackage = null,
            language = "de",
        )
        assertEquals(WordVoiceResolution.Hidden, resolution)
    }

    private fun systemVoice(
        id: String,
        name: String,
        locale: String = "en-US",
        quality: Int = 400,
        latency: Int = 100,
    ) = TtsVoice(
        id = id,
        name = name,
        locale = locale,
        quality = quality,
        latency = latency,
    )

    private fun neuralVoice(
        id: String,
        name: String,
        packageType: NeuralVoicePackage,
    ) = TtsVoice(
        id = id,
        name = name,
        locale = "en-US",
        isNeural = true,
        isDownloaded = true,
    ).also { check(it.neuralVoicePackage == packageType) }
}
