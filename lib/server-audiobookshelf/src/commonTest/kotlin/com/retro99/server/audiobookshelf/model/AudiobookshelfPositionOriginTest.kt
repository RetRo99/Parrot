package com.retro99.server.audiobookshelf.model

import com.retro99.server.api.PositionOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookshelfPositionOriginTest {

    @Test
    fun `a pulled position is remote reading observed at its last update`() = runTest {
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
}
