package com.retro99.server.audiobookshelf.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudiobookshelfBookTimeMappingTest {

    private val fortyFiles = List(40) { _ -> 15L * 60 * 1000 }

    @Test
    fun `a pulled position keeps the book time and finds the file when lengths are known`() {
        // Given
        val progress = AudiobookshelfMediaProgressApiModel(
            currentTime = 18_450.0,
            duration = 36_000.0,
            progress = 0.5125,
        )

        // When
        val known = progress.toServerPosition("item-1", "abs-1", trackDurationsMs = fortyFiles)
        val unknown = progress.toServerPosition("item-1", "abs-1")

        // Then
        assertEquals(18_450_000L, known.bookTimeMs)
        assertEquals(20, known.chapterIndex)
        assertEquals(450_000L, known.audioTimestampMs)
        assertEquals(36_000_000L, known.totalDurationMs)
        assertEquals(0.5125, known.totalProgression)
        assertEquals(18_450_000L, unknown.bookTimeMs)
        assertNull(unknown.chapterIndex)
        assertNull(unknown.audioTimestampMs)
    }

    @Test
    fun `a multi-file position without book time sends no time`() {
        // Given
        val position = AudiobookshelfMediaProgressApiModel(currentTime = 18_450.0)
            .toServerPosition("item-1", "abs-1", trackDurationsMs = fortyFiles)
            .copy(bookTimeMs = null, totalDurationMs = 900_000L, progression = 0.5)

        // When
        val payload = position.toAudiobookshelfMediaProgress(libraryItemId = "item-1")

        // Then
        assertNull(payload.currentTime)
        assertNull(payload.duration)
        assertNull(payload.progress)
    }

    @Test
    fun `a multi-file position is sent as book time`() {
        // Given
        val position = AudiobookshelfMediaProgressApiModel(
            currentTime = 18_450.0,
            duration = 36_000.0,
            progress = 0.5125,
        ).toServerPosition("item-1", "abs-1", trackDurationsMs = fortyFiles)

        // When
        val payload = position.toAudiobookshelfMediaProgress(libraryItemId = "item-1")

        // Then
        assertEquals(18_450.0, payload.currentTime)
        assertEquals(36_000.0, payload.duration)
        assertEquals(0.5125, payload.progress)
    }
}
