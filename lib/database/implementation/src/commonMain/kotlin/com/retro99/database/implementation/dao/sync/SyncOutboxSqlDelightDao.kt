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

    suspend fun getPending(): List<SyncOutboxEntry> {
        return withContext(Dispatchers.IO) {
            queries.getPendingMutations().executeAsList().map { mutation -> mutation.toEntry() }
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
