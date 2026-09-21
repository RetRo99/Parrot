package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncPendingChange
import com.retro99.database.implementation.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal class SyncPendingChangesSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val queries get() = databaseManager.getDatabase().syncPendingChangesQueries

    suspend fun upsert(change: SyncPendingChange) {
        withContext(Dispatchers.IO) {
            queries.upsertPendingChange(
                cloud_user_id = change.cloudUserId,
                change_id = change.changeId,
                entity_type = change.entityType,
                entity_id = change.entityId,
                operation = change.operation,
                payload = change.payload,
                revision = change.revision,
                created_at = change.createdAt,
            )
        }
    }

    suspend fun getPending(cloudUserId: String): List<SyncPendingChange> {
        return withContext(Dispatchers.IO) {
            queries.getPendingChanges(cloudUserId).executeAsList().map { change ->
                SyncPendingChange(
                    cloudUserId = change.cloud_user_id,
                    changeId = change.change_id,
                    entityType = change.entity_type,
                    entityId = change.entity_id,
                    operation = change.operation,
                    payload = change.payload,
                    revision = change.revision,
                    createdAt = change.created_at,
                )
            }
        }
    }

    suspend fun delete(cloudUserId: String, changeId: Long) {
        withContext(Dispatchers.IO) {
            queries.deletePendingChange(cloudUserId, changeId)
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            queries.deleteAllPendingChanges()
        }
    }
}
