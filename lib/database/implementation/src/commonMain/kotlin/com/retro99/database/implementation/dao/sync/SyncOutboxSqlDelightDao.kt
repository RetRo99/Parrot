package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal class SyncOutboxSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val queries get() = databaseManager.getDatabase().syncOutboxQueries

    suspend fun enqueue(entry: SyncOutboxEntry) {
        withContext(Dispatchers.IO) {
            queries.enqueue(entry)
        }
    }

    suspend fun bindUnassignedMutations(cloudUserId: String) {
        withContext(Dispatchers.IO) {
            queries.bindUnassignedMutations(cloudUserId)
        }
    }

    suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> {
        return withContext(Dispatchers.IO) {
            queries.getPendingMutations(cloudUserId)
                .executeAsList()
                .map { mutation -> mutation.toEntry() }
        }
    }

    suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) {
        withContext(Dispatchers.IO) {
            queries.updateBaseRevision(baseRevision, mutationId)
        }
    }

    suspend fun delete(mutationId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteMutation(mutationId)
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            queries.deleteAllMutations()
        }
    }
}
