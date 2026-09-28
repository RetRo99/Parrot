package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtsPreparationProgressTest {

    @Test
    fun `downloading calculates bounded progress from byte counts`() {
        // Given
        val halfway = TtsPreparationProgress.Downloading(
            downloadedBytes = 50L,
            totalBytes = 100L,
        )
        val pastEnd = halfway.copy(downloadedBytes = 120L)

        // When
        val halfwayFraction = halfway.fraction
        val halfwayPercentage = halfway.percentage

        // Then
        assertEquals(0.5f, halfwayFraction)
        assertEquals(50, halfwayPercentage)
        assertEquals(1f, pastEnd.fraction)
        assertEquals(100, pastEnd.percentage)
    }

    @Test
    fun `downloading has indeterminate progress when total size is unknown`() {
        // Given
        val progress = TtsPreparationProgress.Downloading(
            downloadedBytes = 50L,
            totalBytes = null,
        )

        // When
        val fraction = progress.fraction
        val percentage = progress.percentage

        // Then
        assertNull(fraction)
        assertNull(percentage)
    }
}
