package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.server.api.ServerPosition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParrotCloudProgressPayloadMapperTest {
    @Test
    fun mapsLocalReaderProgressToLinkedCloudAndLibraryBookIdentity() {
        val libraryBook = TestLibraryBook(
            libraryBookId = "sha-256-v1:content-hash",
            cloudBookId = "cloud-book-id",
        )
        val localMutation = LocalReadingPositionMutation(
            bookUuid = "local-book-id",
            contentHash = "content-hash",
            contentHashAlgorithm = "sha-256-v1",
            position = ServerPosition(
                bookUuid = "local-book-id",
                serverId = "local",
                libraryBookId = "sha-256-v1:content-hash",
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
        )

        val payload = localMutation.toParrotCloudReadingPositionPayload(
            libraryBook = libraryBook,
            cloudBookId = "cloud-book-id",
        )

        assertEquals("cloud-book-id", payload.cloudBookId)
        assertEquals("sha-256-v1:content-hash", payload.libraryBookId)
        assertEquals("local-book-id", payload.position.bookUuid)
        assertEquals(PARROT_CLOUD_SERVER_ID, payload.position.serverId)
        assertEquals(0.1, payload.position.totalProgression)

        val encoded = Json.Default.encodeToString(payload)
        assertTrue("\"cloud_book_id\":\"cloud-book-id\"" in encoded)
        assertTrue("\"library_book_id\":\"sha-256-v1:content-hash\"" in encoded)
    }
}

private data class TestLibraryBook(
    override val libraryBookId: String,
    override val cloudBookId: String?,
) : LibraryBookEntity {
    override val contentHash: String? = "content-hash"
    override val title: String = "Test book"
    override val author: String? = null
    override val format: String = "epub"
}
