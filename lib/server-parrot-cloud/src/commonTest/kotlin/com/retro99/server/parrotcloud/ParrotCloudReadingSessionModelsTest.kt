package com.retro99.server.parrotcloud

import com.retro99.database.api.statistics.ReadingSessionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ParrotCloudReadingSessionModelsTest {

    @Test
    fun sessionIdChangesWhenAnyIdentityFieldChanges() {
        assertNotEquals(sessionId(), sessionId(bookUuid = "book-b"))
        assertNotEquals(sessionId(), sessionId(bookType = "readaloud"))
        assertNotEquals(sessionId(), sessionId(startTime = 1_001L))
        assertNotEquals(sessionId(), sessionId(endTime = 2_001L))
        assertNotEquals(sessionId(), sessionId(durationMs = 999L))
    }

    @Test
    fun sessionIdEscapesSeparatorCharacters() {
        // Distinct sessions must never collapse into one cloud identity.
        assertNotEquals(sessionId(bookUuid = "book_a"), sessionId(bookUuid = "book|a"))
        assertNotEquals(sessionId(bookUuid = "a"), sessionId(bookUuid = "a\\"))
        assertNotEquals(
            sessionId(bookUuid = "a", bookType = "ebook"),
            sessionId(bookUuid = "a|ebook", bookType = "ebook"),
        )
    }

    @Test
    fun payloadRoundTripPreservesDerivedIdentity() {
        val payload = session().toParrotCloudReadingSessionPayload()
        val restored = payload.toReadingSessionEntity().toParrotCloudReadingSessionPayload()
        assertEquals(payload, restored)
    }

    @Test
    fun appliedSessionKeepsTheOriginBookUuidVerbatim() {
        val entity = session().toParrotCloudReadingSessionPayload().toReadingSessionEntity()
        assertEquals("book-a", entity.bookUuid)
        assertEquals("First Book", entity.bookTitle)
        assertEquals(250, entity.readingSpeedWpm)
    }

    private fun sessionId(
        bookUuid: String = "book-a",
        bookType: String = "ebook",
        startTime: Long = 1_000L,
        endTime: Long = 2_000L,
        durationMs: Long = 1_000L,
    ): String {
        return readingSessionSyncId(
            bookUuid = bookUuid,
            bookType = bookType,
            startTime = startTime,
            endTime = endTime,
            durationMs = durationMs,
        )
    }

    private fun session(): ReadingSessionEntity {
        return TestReadingSession(
            id = 7L,
            bookUuid = "book-a",
            bookTitle = "First Book",
            bookType = "ebook",
            startTime = 1_000L,
            endTime = 2_000L,
            durationMs = 1_000L,
            pagesRead = 12,
            startProgression = 0.1,
            endProgression = 0.2,
            readingSpeedWpm = 250,
        )
    }
}
