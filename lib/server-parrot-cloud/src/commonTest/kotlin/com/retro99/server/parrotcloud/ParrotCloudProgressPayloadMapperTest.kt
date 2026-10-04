package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.server.api.ServerPosition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParrotCloudProgressPayloadMapperTest {

    @Test
    fun `local reader progress is sent under the library book id`() {
        // Given
        val localMutation = LocalReadingPositionMutation(
            bookUuid = BOOK_ID,
            position = ServerPosition(
                bookUuid = BOOK_ID,
                serverId = "local",
                libraryBookId = BOOK_ID,
                timestamp = 1L,
                createdAt = "2026-09-24T00:00:00Z",
                updatedAt = "2026-09-24T00:01:00Z",
                locatorHref = "chapter-1.xhtml",
                locatorType = "application/xhtml+xml",
                locatorTitle = "Chapter 1",
                locatorTarget = null,
                audioTimestampMs = null,
                chapterIndex = 0,
                progression = 0.1,
                totalChapters = 61,
                totalDurationMs = null,
                totalProgression = 0.1,
                position = 4,
            ),
            sourceDevice = com.retro99.server.api.SourceDeviceIdentity(
                id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                name = "Pixel Tablet",
            ),
        )

        // When
        val payload = localMutation.toParrotCloudReadingPositionPayload(parrotTestBook(BOOK_ID))

        // Then
        assertEquals(BOOK_ID, payload.libraryBookId)
        assertEquals(BOOK_ID, payload.position.bookUuid)
        assertEquals(PARROT_CLOUD_SERVER_ID, payload.position.serverId)
        assertEquals(0.1, payload.position.totalProgression)
        assertEquals("Pixel Tablet", payload.sourceDevice?.name)
        val encoded = Json.Default.encodeToString(payload)
        assertTrue("\"library_book_id\":\"$BOOK_ID\"" in encoded)
        assertTrue("\"source_device\"" in encoded)
        assertFalse("cloud_book_id" in encoded)
    }

    @Test
    fun `a pulled position without cloud_book_id decodes`() {
        // Given
        val pulled = """{"library_book_id":"$BOOK_ID","position":{"bookUuid":"$BOOK_ID",""" +
            """"serverId":"parrot-cloud","timestamp":null,"createdAt":null,"updatedAt":null,""" +
            """"locatorHref":null,"locatorType":null,"locatorTitle":null,"locatorTarget":null,""" +
            """"audioTimestampMs":null,"chapterIndex":null,"progression":0.5,""" +
            """"totalChapters":null,"totalDurationMs":null,"totalProgression":0.5,""" +
            """"position":null}}"""

        // When
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString<ParrotCloudReadingPositionPayload>(pulled)

        // Then
        assertEquals(BOOK_ID, payload.libraryBookId)
        assertEquals(0.5, payload.position.progression)
        assertEquals(null, payload.sourceDevice)
    }

    private companion object {
        const val BOOK_ID = "33333333-3333-4333-8333-333333333333"
    }
}
