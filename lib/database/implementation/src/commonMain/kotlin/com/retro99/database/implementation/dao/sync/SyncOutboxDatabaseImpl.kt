package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry

internal class SyncOutboxDatabaseImpl(
    private val sqlDelightDao: SyncOutboxSqlDelightDao,
) : SyncOutboxDatabase {

    override suspend fun enqueue(entry: SyncOutboxEntry) {
        sqlDelightDao.enqueue(entry)
    }

    override suspend fun bindUnassignedMutations(cloudUserId: String) {
        sqlDelightDao.bindUnassignedMutations(cloudUserId)
    }

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> {
        return sqlDelightDao.getPending(cloudUserId)
    }

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) {
        sqlDelightDao.updateBaseRevision(mutationId, baseRevision)
    }

    override suspend fun delete(mutationId: String) {
        sqlDelightDao.delete(mutationId)
    }

    override suspend fun clearAllData() {
        sqlDelightDao.deleteAll()
    }
}
