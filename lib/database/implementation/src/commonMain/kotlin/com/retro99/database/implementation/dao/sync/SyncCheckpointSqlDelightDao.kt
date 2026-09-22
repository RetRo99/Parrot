package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.implementation.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal class SyncCheckpointSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val queries get() = databaseManager.getDatabase().syncCheckpointQueries

    suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint? {
        return withContext(Dispatchers.IO) {
            queries
                .getCheckpoint(destinationId, remoteAccountId)
                .executeAsOneOrNull()
                ?.toCheckpoint()
        }
    }

    suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        withContext(Dispatchers.IO) {
            queries.upsert(checkpoint)
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            queries.deleteAllCheckpoints()
        }
    }
}
