package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry

internal class SyncOutboxDatabaseImpl(
    private val sqlDelightDao: SyncOutboxSqlDelightDao,
) : SyncOutboxDatabase {

    override suspend fun enqueue(entry: SyncOutboxEntry) {
        sqlDelightDao.enqueue(entry)
    }

    override suspend fun getPending(): List<SyncOutboxEntry> {
        return sqlDelightDao.getPending()
    }

    override suspend fun delete(mutationId: String) {
        sqlDelightDao.delete(mutationId)
    }

    override suspend fun clearAllData() {
        sqlDelightDao.deleteAll()
    }
}
