package com.retro99.server.parrotcloud

import com.retro99.database.api.statistics.BookReadingStatsEntity
import com.retro99.database.api.statistics.DailyReadingTimeEntity
import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.database.api.statistics.ReadingSessionEntity
import com.retro99.database.api.reader.ReaderSettingsDatabase
import com.retro99.database.api.reader.ReaderSettingsEntity
import com.retro99.database.api.reader.ReaderSettingsMutation
import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal data class TestReadingSession(
    override val id: Long,
    override val bookUuid: String,
    override val bookTitle: String,
    override val bookType: String,
    override val startTime: Long,
    override val endTime: Long,
    override val durationMs: Long,
    override val pagesRead: Int? = null,
    override val startProgression: Double? = null,
    override val endProgression: Double? = null,
    override val readingSpeedWpm: Int? = null,
) : ReadingSessionEntity

internal class RecordingReadingSessionDatabase(
    private val sessions: List<ReadingSessionEntity> = emptyList(),
) : ReadingSessionDatabase {
    val inserted = mutableListOf<ReadingSessionEntity>()

    override suspend fun insertSession(session: ReadingSessionEntity) {
        inserted += session
    }

    override suspend fun getAllSessions(): List<ReadingSessionEntity> = sessions + inserted

    override suspend fun getSessionsByBookUuid(bookUuid: String): List<ReadingSessionEntity> {
        return getAllSessions().filter { session -> session.bookUuid == bookUuid }
    }

    override suspend fun getSessionsInDateRange(
        startTime: Long,
        endTime: Long,
    ): List<ReadingSessionEntity> = emptyList()

    override suspend fun getTotalReadingTimeMs(): Long = 0L

    override suspend fun getTotalReadingTimeMsInDateRange(
        startTime: Long,
        endTime: Long,
    ): Long = 0L

    override suspend fun getSessionCountInDateRange(
        startTime: Long,
        endTime: Long,
    ): Long = 0L

    override suspend fun getDistinctBooksReadInDateRange(
        startTime: Long,
        endTime: Long,
    ): Long = 0L

    override suspend fun getRecentSessions(limit: Int): List<ReadingSessionEntity> = emptyList()

    override suspend fun getDailyReadingTime(
        sinceTimestamp: Long,
    ): List<DailyReadingTimeEntity> = emptyList()

    override suspend fun getReadingTimeByBookType(
        startTime: Long,
        endTime: Long,
    ): Map<String, Long> = emptyMap()

    override suspend fun getMostReadBooks(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<BookReadingStatsEntity> = emptyList()

    override suspend fun getReadingDays(sinceTimestamp: Long): List<Long> = emptyList()

    override suspend fun getSessionsAfterId(
        afterId: Long,
        limit: Int,
    ): List<ReadingSessionEntity> {
        return sessions.filter { session -> session.id > afterId }
            .sortedBy { session -> session.id }
            .take(limit)
    }

    override suspend fun getSessionByNaturalKey(
        bookUuid: String,
        bookType: String,
        startTime: Long,
        endTime: Long,
        durationMs: Long,
    ): ReadingSessionEntity? {
        return getAllSessions().firstOrNull { session ->
            session.bookUuid == bookUuid &&
                session.bookType == bookType &&
                session.startTime == startTime &&
                session.endTime == endTime &&
                session.durationMs == durationMs
        }
    }

    override suspend fun deleteSession(id: Long) = Unit

    override suspend fun deleteAllSessions() {
        inserted.clear()
    }
}

internal class RecordingSyncOutboxDatabase : SyncOutboxDatabase {
    val enqueued = mutableListOf<SyncOutboxEntry>()

    override suspend fun enqueue(entry: SyncOutboxEntry) {
        enqueued += entry
    }

    override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = enqueued

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) = Unit

    override suspend fun markConflict(mutationId: String, error: String) = Unit

    override suspend fun delete(mutationId: String) = Unit

    override suspend fun deleteByEntityType(entityType: String) = Unit

    override suspend fun recordFailure(
        mutationId: String,
        nextAttemptAt: String,
        error: String,
    ) = Unit

    override suspend fun coalesce(
        entityType: String,
        entityId: String,
        entry: SyncOutboxEntry,
    ) = Unit

    override suspend fun clearAllData() {
        enqueued.clear()
    }
}

internal fun testReaderSettingsSync(): ParrotCloudReaderSettingsSync = ParrotCloudReaderSettingsSync(
    readerSettingsDatabase = object : ReaderSettingsDatabase {
        override suspend fun getAll(): List<ReaderSettingsEntity> = emptyList()
        override fun observeAll(): Flow<List<ReaderSettingsEntity>> = emptyFlow()
        override suspend fun upsertSettings(settings: List<ReaderSettingsEntity>) = Unit
        override suspend fun upsertSettingsWithMutations(mutations: List<ReaderSettingsMutation>) = Unit
    },
    syncOutboxDatabase = RecordingSyncOutboxDatabase(),
)

internal class InMemorySyncCheckpointDatabase : SyncCheckpointDatabase {
    private val checkpoints = mutableMapOf<Pair<String, String>, SyncCheckpoint>()

    override suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint? {
        return checkpoints[destinationId to remoteAccountId]
    }

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        checkpoints[checkpoint.destinationId to checkpoint.remoteAccountId] = checkpoint
    }

    override suspend fun clearAllData() {
        checkpoints.clear()
    }
}
