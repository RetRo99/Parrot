package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ParrotCloudReadingSessionSyncServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun firstSweepBackfillsAllSessionsAndAdvancesTheMarker() = runTest {
        val sessions = listOf(
            session(id = 1L, bookUuid = "book-a"),
            session(id = 2L, bookUuid = "book-b"),
            session(id = 3L, bookUuid = "book-a", startTime = 3_000L, endTime = 4_000L),
        )
        val database = RecordingReadingSessionDatabase(sessions = sessions)
        val outbox = RecordingSyncOutboxDatabase()
        val checkpoints = InMemorySyncCheckpointDatabase()
        val service = ParrotCloudReadingSessionSyncService(database, outbox, checkpoints)

        service.enqueueNewSessions("account-1")

        assertEquals(3, outbox.enqueued.size)
        assertEquals("3", checkpoints.getCheckpoint("__reading_session_sweep__", "account-1")?.cursor)
    }

    @Test
    fun laterSweepsEnqueueOnlySessionsAfterTheMarker() = runTest {
        val sessions = listOf(
            session(id = 1L, bookUuid = "book-a"),
            session(id = 2L, bookUuid = "book-b"),
            session(id = 3L, bookUuid = "book-a", startTime = 3_000L, endTime = 4_000L),
        )
        val database = RecordingReadingSessionDatabase(sessions = sessions)
        val outbox = RecordingSyncOutboxDatabase()
        val checkpoints = InMemorySyncCheckpointDatabase()
        val service = ParrotCloudReadingSessionSyncService(database, outbox, checkpoints)

        service.enqueueNewSessions("account-1")
        outbox.enqueued.clear()
        service.enqueueNewSessions("account-1")

        assertEquals(emptyList<SyncOutboxEntry>(), outbox.enqueued)
    }

    @Test
    fun enqueuedEntriesCarryTheDerivedSessionIdentity() = runTest {
        val sessions = listOf(session(id = 9L, bookUuid = "book-a"))
        val outbox = RecordingSyncOutboxDatabase()
        val service = ParrotCloudReadingSessionSyncService(
            RecordingReadingSessionDatabase(sessions = sessions),
            outbox,
            InMemorySyncCheckpointDatabase(),
        )

        service.enqueueNewSessions("account-1")

        val entry = outbox.enqueued.single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_READING_SESSION, entry.entityType)
        assertEquals(SyncOutboxEntry.OPERATION_UPSERT, entry.operation)
        assertEquals("account-1", entry.cloudUserId)
        assertEquals("rs1|book-a|ebook|1000|2000|1000", entry.entityId)
        assertEquals(
            "rs1|book-a|ebook|1000|2000|1000",
            json.decodeFromString<ParrotCloudReadingSessionPayload>(entry.payload).sessionId,
        )
    }

    @Test
    fun reSweepingTheSameSessionCollapsesIntoOneIdentity() = runTest {
        val sessions = listOf(session(id = 1L, bookUuid = "book-a"))
        val outbox = RecordingSyncOutboxDatabase()
        val checkpoints = InMemorySyncCheckpointDatabase()
        val service = ParrotCloudReadingSessionSyncService(
            RecordingReadingSessionDatabase(sessions = sessions),
            outbox,
            checkpoints,
        )

        service.enqueueNewSessions("account-1")
        // Simulate a lost marker: the same local row is swept again and its
        // derived identity must match so the server treats it as a no-op.
        checkpoints.clearAllData()
        service.enqueueNewSessions("account-1")

        assertEquals(2, outbox.enqueued.size)
        assertEquals(
            outbox.enqueued.first().entityId,
            outbox.enqueued.last().entityId,
        )
        assertEquals(outbox.enqueued.first().payload, outbox.enqueued.last().payload)
    }

    @Test
    fun sweepChunksPastTheBatchSizeAndTerminates() = runTest {
        val sessions = (1L..501L).map { id -> session(id = id, bookUuid = "book-$id") }
        val database = RecordingReadingSessionDatabase(sessions = sessions)
        val outbox = RecordingSyncOutboxDatabase()
        val checkpoints = InMemorySyncCheckpointDatabase()
        val service = ParrotCloudReadingSessionSyncService(database, outbox, checkpoints)

        service.enqueueNewSessions("account-1")

        assertEquals(501, outbox.enqueued.size)
        assertEquals("501", checkpoints.getCheckpoint("__reading_session_sweep__", "account-1")?.cursor)

        outbox.enqueued.clear()
        service.enqueueNewSessions("account-1")
        assertEquals(emptyList<SyncOutboxEntry>(), outbox.enqueued)
    }

    @Test
    fun degenerateSessionsAreSkippedButDoNotBlockTheSweep() = runTest {
        val sessions = listOf(
            session(id = 1L, bookUuid = "book-a").copy(bookTitle = ""),
            session(id = 2L, bookUuid = "book-b"),
        )
        val outbox = RecordingSyncOutboxDatabase()
        val checkpoints = InMemorySyncCheckpointDatabase()
        val service = ParrotCloudReadingSessionSyncService(
            RecordingReadingSessionDatabase(sessions = sessions),
            outbox,
            checkpoints,
        )

        service.enqueueNewSessions("account-1")

        assertEquals(1, outbox.enqueued.size)
        assertEquals("rs1|book-b|ebook|1000|2000|1000", outbox.enqueued.single().entityId)
        assertEquals("2", checkpoints.getCheckpoint("__reading_session_sweep__", "account-1")?.cursor)
    }

    private fun session(
        id: Long,
        bookUuid: String,
        startTime: Long = 1_000L,
        endTime: Long = 2_000L,
    ) = TestReadingSession(
        id = id,
        bookUuid = bookUuid,
        bookTitle = "First Book",
        bookType = "ebook",
        startTime = startTime,
        endTime = endTime,
        durationMs = endTime - startTime,
        readingSpeedWpm = 250,
    )
}
