package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsVoicePreparationStateHolderTest {

    @Test
    fun `begin keeps one active voice preparation`() {
        // Given
        val classUnderTest = TtsVoicePreparationStateHolder()
        val firstProgress = TtsPreparationProgress.Downloading(10L, 100L)
        val secondProgress = TtsPreparationProgress.Downloading(20L, 100L)

        // When
        val firstStarted = classUnderTest.begin(NeuralVoicePackage.KOKORO, firstProgress)
        val secondStarted = classUnderTest.begin(
            NeuralVoicePackage.SUPERTONIC,
            secondProgress,
        )

        // Then
        assertTrue(firstStarted)
        assertFalse(secondStarted)
        assertEquals(
            TtsVoicePreparationState.Running(NeuralVoicePackage.KOKORO, firstProgress),
            classUnderTest.state.value,
        )
    }

    @Test
    fun `progress and completion are observable across readers`() {
        // Given
        val classUnderTest = TtsVoicePreparationStateHolder()
        val initialProgress = TtsPreparationProgress.Downloading(0L, 100L)
        val updatedProgress = TtsPreparationProgress.Downloading(60L, 100L)
        classUnderTest.begin(NeuralVoicePackage.KOKORO, initialProgress)

        // When
        classUnderTest.updateProgress(NeuralVoicePackage.KOKORO, updatedProgress)

        // Then
        assertEquals(
            TtsVoicePreparationState.Running(NeuralVoicePackage.KOKORO, updatedProgress),
            classUnderTest.state.value,
        )

        // When
        classUnderTest.markComplete(NeuralVoicePackage.KOKORO)

        // Then
        assertEquals(
            TtsVoicePreparationState.Complete(NeuralVoicePackage.KOKORO),
            classUnderTest.state.value,
        )
    }

    @Test
    fun `clearing a finished state drops a failure a later reader never started`() {
        // Given
        val classUnderTest = TtsVoicePreparationStateHolder()
        classUnderTest.begin(NeuralVoicePackage.KOKORO, TtsPreparationProgress.Downloading(10L, 100L))
        classUnderTest.markFailed(NeuralVoicePackage.KOKORO)

        // When
        classUnderTest.clearFinishedState()

        // Then
        assertEquals(TtsVoicePreparationState.Idle, classUnderTest.state.value)
    }

    @Test
    fun `clearing a finished state drops a completion`() {
        // Given
        val classUnderTest = TtsVoicePreparationStateHolder()
        classUnderTest.begin(NeuralVoicePackage.KOKORO, TtsPreparationProgress.Downloading(10L, 100L))
        classUnderTest.markComplete(NeuralVoicePackage.KOKORO)

        // When
        classUnderTest.clearFinishedState()

        // Then
        assertEquals(TtsVoicePreparationState.Idle, classUnderTest.state.value)
    }

    @Test
    fun `clearing a finished state leaves a running preparation and its progress`() {
        // Given
        val classUnderTest = TtsVoicePreparationStateHolder()
        val progress = TtsPreparationProgress.Downloading(40L, 100L)
        classUnderTest.begin(NeuralVoicePackage.KOKORO, progress)

        // When
        classUnderTest.clearFinishedState()

        // Then
        assertEquals(
            TtsVoicePreparationState.Running(NeuralVoicePackage.KOKORO, progress),
            classUnderTest.state.value,
        )
    }

    @Test
    fun `updates from another package cannot complete the active preparation`() {
        // Given
        val classUnderTest = TtsVoicePreparationStateHolder()
        val kokoroProgress = TtsPreparationProgress.Downloading(10L, 100L)
        val supertonicProgress = TtsPreparationProgress.Downloading(20L, 100L)
        classUnderTest.begin(NeuralVoicePackage.KOKORO, kokoroProgress)

        // When
        classUnderTest.updateProgress(NeuralVoicePackage.SUPERTONIC, supertonicProgress)
        classUnderTest.markComplete(NeuralVoicePackage.SUPERTONIC)

        // Then
        assertEquals(
            TtsVoicePreparationState.Running(NeuralVoicePackage.KOKORO, kokoroProgress),
            classUnderTest.state.value,
        )
    }
}
