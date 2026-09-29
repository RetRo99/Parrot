package com.retro99.server.parrotcloud

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParrotCloudReadingSessionChangeApplierTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun insertsRemoteSessionWhenNaturalKeyIsAbsent() = runTest {
        val database = RecordingReadingSessionDatabase()
        val applier = ParrotCloudReadingSessionChangeApplier(database)

        applier.apply(json.encodeToJsonElement(payload()))

        val inserted = database.inserted.single()
        assertEquals("book-a", inserted.bookUuid)
        assertEquals(1_000L, inserted.startTime)
        assertEquals(1_000L, inserted.durationMs)
    }

    @Test
    fun skipsRemoteSessionAlreadyPresentLocally() = runTest {
        val existing = TestReadingSession(
            id = 1L,
            bookUuid = "book-a",
            bookTitle = "First Book",
            bookType = "ebook",
            startTime = 1_000L,
            endTime = 2_000L,
            durationMs = 1_000L,
        )
        val database = RecordingReadingSessionDatabase(sessions = listOf(existing))
        val applier = ParrotCloudReadingSessionChangeApplier(database)

        applier.apply(json.encodeToJsonElement(payload()))

        assertTrue(database.inserted.isEmpty())
    }

    @Test
    fun insertsDistinctSessionForTheSameTimeWindowOnAnotherBook() = runTest {
        val existing = TestReadingSession(
            id = 1L,
            bookUuid = "other-book",
            bookTitle = "Other Book",
            bookType = "ebook",
            startTime = 1_000L,
            endTime = 2_000L,
            durationMs = 1_000L,
        )
        val database = RecordingReadingSessionDatabase(sessions = listOf(existing))
        val applier = ParrotCloudReadingSessionChangeApplier(database)

        applier.apply(json.encodeToJsonElement(payload()))

        assertEquals(1, database.inserted.size)
    }

    @Test
    fun malformedPayloadFailsSoThePullCanRetry() = runTest {
        val database = RecordingReadingSessionDatabase()
        val applier = ParrotCloudReadingSessionChangeApplier(database)

        assertFailsWith<SerializationException> {
            applier.apply(json.parseToJsonElement("""{"session_id":"rs1|x"}"""))
        }

        assertTrue(database.inserted.isEmpty())
    }

    private fun payload() = ParrotCloudReadingSessionPayload(
        sessionId = "rs1|book-a|ebook|1000|2000|1000",
        bookUuid = "book-a",
        bookTitle = "First Book",
        bookType = "ebook",
        startTime = 1_000L,
        endTime = 2_000L,
        durationMs = 1_000L,
        readingSpeedWpm = 250,
    )
}
