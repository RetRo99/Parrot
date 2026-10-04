package com.retro99.server.implementation.source

import com.retro99.server.api.ServerPosition
import com.retro99.server.api.SourceDeviceIdentity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerPositionDeviceAttributionTest {
    @Test
    fun `the original device identity remains in the durable position mutation`() {
        val source = SourceDeviceIdentity(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            name = "Pixel Tablet",
        )
        val mutation = testPosition(
            sourceDeviceId = source.id,
            deviceName = source.name,
        ).toSyncOutboxEntry(baseRevision = 12L, localGeneration = 4L)

        val restored = Json { ignoreUnknownKeys = true }
            .decodeFromString<LocalReadingPositionMutation>(mutation.payload)

        assertEquals(source, restored.sourceDevice)
        assertEquals(12L, mutation.baseRevision)
        assertEquals(4L, mutation.localGeneration)
    }

    private fun testPosition(
        sourceDeviceId: String,
        deviceName: String?,
    ) = ServerPosition(
        bookUuid = "book-1",
        serverId = "local",
        timestamp = 1L,
        createdAt = null,
        updatedAt = null,
        locatorHref = "chapter-1.xhtml",
        locatorType = "application/xhtml+xml",
        locatorTitle = "Chapter 1",
        locatorTarget = null,
        audioTimestampMs = null,
        chapterIndex = null,
        progression = 0.4,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = 0.4,
        position = null,
        sourceDeviceId = sourceDeviceId,
        deviceName = deviceName,
    )
}
