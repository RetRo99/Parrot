package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.CloudPendingChangesRepository
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CloudPendingChangesRepository::class])
class CloudPendingChangesDataRepository(
    @Provided private val database: SyncOutboxDatabase,
    @Provided private val files: CloudFilesDatabase,
    @Provided private val sessions: ReadingSessionDatabase,
    @Provided private val checkpoints: SyncCheckpointDatabase,
) : CloudPendingChangesRepository {
    override suspend fun count(cloudUserId: String): Int {
        val mutations = database.getPendingIncludingUnassigned(cloudUserId).count { isCloudMutation(it.entityType) }
        val uploads = files.getTransfers("parrot-cloud", listOf("pending", "transferring", "verifying", "finalizing", "failed"))
            .count { it.direction == "upload" }
        // Reading history is swept into the outbox only when sync starts. Count it
        // without enqueueing anything or changing the sweep checkpoint.
        var lastId = checkpoints.getCheckpoint("__reading_session_sweep__", cloudUserId)?.cursor?.toLongOrNull() ?: 0L
        var unswept = 0
        while (true) {
            val batch = sessions.getSessionsAfterId(lastId, 500)
            if (batch.isEmpty()) break
            unswept += batch.count { it.bookUuid.isNotBlank() && it.bookTitle.isNotBlank() && it.bookType.isNotBlank() }
            lastId = batch.last().id
        }
        return mutations + uploads + unswept
    }
}

internal fun isCloudMutation(entityType: String): Boolean = entityType in setOf(
    SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
    SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
    SyncOutboxEntry.ENTITY_TYPE_READING_SESSION,
    SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
    SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
)
