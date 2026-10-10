package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The router's readiness gate, which hides the word speaker and holds the voice list. The
 * three engines need Android, so the rule under test is the top-level decision function.
 */
class TtsSynthesizerRouterTest {

    @Test
    fun `an installed neural pack is ready without waiting for the system engine`() = runTest {
        // Given
        val systemWaits = mutableListOf<Long>()

        // When
        val ready = awaitSynthesizerReady(
            timeoutMs = 3_000L,
            isNeuralPackUsable = { true },
            awaitSystemReady = { timeout ->
                systemWaits += timeout
                false
            },
        )

        // Then
        assertTrue(ready)
    }

    @Test
    fun `an installed neural pack never waits on the system engine`() = runTest {
        // Given
        val systemWaits = mutableListOf<Long>()

        // When
        awaitSynthesizerReady(
            timeoutMs = 3_000L,
            isNeuralPackUsable = { true },
            awaitSystemReady = { timeout ->
                systemWaits += timeout
                true
            },
        )

        // Then
        assertEquals(emptyList(), systemWaits)
    }

    @Test
    fun `with no neural pack a ready system engine is ready`() = runTest {
        // Given
        val systemWaits = mutableListOf<Long>()

        // When
        val ready = awaitSynthesizerReady(
            timeoutMs = 3_000L,
            isNeuralPackUsable = { false },
            awaitSystemReady = { timeout ->
                systemWaits += timeout
                true
            },
        )

        // Then
        assertTrue(ready)
        assertEquals(listOf(3_000L), systemWaits)
    }

    @Test
    fun `with no neural pack a system engine that never arrives is not ready`() = runTest {
        // Given
        val systemWaits = mutableListOf<Long>()

        // When
        val ready = awaitSynthesizerReady(
            timeoutMs = 3_000L,
            isNeuralPackUsable = { false },
            awaitSystemReady = { timeout ->
                systemWaits += timeout
                false
            },
        )

        // Then
        assertFalse(ready)
        assertEquals(listOf(3_000L), systemWaits)
    }
}
