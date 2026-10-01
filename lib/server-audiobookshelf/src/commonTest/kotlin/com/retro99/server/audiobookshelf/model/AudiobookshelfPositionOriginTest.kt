package com.retro99.server.audiobookshelf.model

import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookshelfPositionOriginTest {

    @Test
    fun `a pulled position is remote reading observed at its last update`() {
        // Given
        val progress = AudiobookshelfMediaProgressApiModel(
            libraryItemId = "item-1",
            currentTime = 12.0,
            duration = 100.0,
            progress = 0.12,
            lastUpdate = 1_000L,
        )

        // When
        val position = progress.toServerPosition(bookUuid = "item-1", serverId = "abs-1")

        // Then
        assertEquals(PositionOrigin.Remote, position.origin)
        assertEquals("1970-01-01T00:00:01Z", position.observedAt)
    }

    @Test
    fun `the payload ignores origin, observation time and text anchor`() {
        // Given
        val plain = AudiobookshelfMediaProgressApiModel(
            libraryItemId = "item-1",
            currentTime = 12.0,
            duration = 100.0,
            progress = 0.12,
            lastUpdate = 1_000L,
        ).toServerPosition(bookUuid = "item-1", serverId = "abs-1")
        val annotated = plain.copy(
            origin = PositionOrigin.Manual,
            observedAt = "2026-10-01T10:00:00Z",
            textAnchor = TextAnchor(before = "before", after = "after"),
        )

        // When
        val payload = annotated.toAudiobookshelfMediaProgress(libraryItemId = "item-1")

        // Then
        assertEquals(plain.toAudiobookshelfMediaProgress(libraryItemId = "item-1"), payload)
    }
}
