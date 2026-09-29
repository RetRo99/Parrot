package com.retro99.server.parrotcloud

import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.database.api.statistics.ReadingSessionEntity
import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

/**
 * Sweeps local reading sessions into the sync outbox for Parrot Cloud.
 *
 * Statistics live only on-device and the local database is recreated from
 * scratch whenever its schema version changes, so sessions carry no sync
 * identity column and cannot grow one safely. Instead a durable per-account
 * checkpoint remembers the highest local session id already enqueued:
 *
 * - the first sweep for an account backfills the full local history,
 * - later sweeps enqueue only sessions recorded since the previous sweep,
 * - a crash between enqueueing and checkpointing only re-enqueues rows, whose
 *   derived session ids collapse again on the server.
 */
@Single
class ParrotCloudReadingSessionSyncService(
    @Provided private val readingSessionDatabase: ReadingSessionDatabase,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val syncCheckpointDatabase: SyncCheckpointDatabase,
) {

    suspend fun enqueueNewSessions(cloudUserId: String) {
        var lastSweptId = syncCheckpointDatabase
            .getCheckpoint(SWEEP_DESTINATION_ID, cloudUserId)
            ?.cursor
            ?.toLongOrNull()
            ?: 0L
        while (true) {
            val sessions = readingSessionDatabase.getSessionsAfterId(lastSweptId, SWEEP_CHUNK_SIZE)
            if (sessions.isEmpty()) break
            sessions.filter { session -> session.hasSyncableIdentity() }
                .forEach { session ->
                    syncOutboxDatabase.enqueue(session.toSyncOutboxEntry(cloudUserId))
                }
            lastSweptId = sessions.last().id
            syncCheckpointDatabase.saveCheckpoint(
                SyncCheckpoint(
                    destinationId = SWEEP_DESTINATION_ID,
                    remoteAccountId = cloudUserId,
                    cursor = lastSweptId.toString(),
                    updatedAt = Clock.System.now().toString(),
                ),
            )
        }
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun ReadingSessionEntity.toSyncOutboxEntry(cloudUserId: String): SyncOutboxEntry {
        val payload = toParrotCloudReadingSessionPayload()
        return SyncOutboxEntry.new(
            entityType = SyncOutboxEntry.ENTITY_TYPE_READING_SESSION,
            entityId = payload.sessionId,
            operation = SyncOutboxEntry.OPERATION_UPSERT,
            payload = json.encodeToString(payload),
            cloudUserId = cloudUserId,
        )
    }

    private companion object {
        // Not a real sync destination: reuses checkpoint storage as a durable
        // per-account sweep marker, mirroring the diagnostics destination id.
        const val SWEEP_DESTINATION_ID = "__reading_session_sweep__"
        const val SWEEP_CHUNK_SIZE = 500
    }
}

/**
 * The server rejects sessions with blank identity fields and a rejected outbox
 * entry retries forever, so degenerate local rows are never enqueued.
 */
private fun ReadingSessionEntity.hasSyncableIdentity(): Boolean {
    return bookUuid.isNotBlank() && bookTitle.isNotBlank() && bookType.isNotBlank()
}
