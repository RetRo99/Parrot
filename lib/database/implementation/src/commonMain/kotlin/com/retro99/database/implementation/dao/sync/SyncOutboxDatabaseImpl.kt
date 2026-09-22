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

    override suspend fun getEligible(
        cloudUserId: String,
        now: String,
    ): List<SyncOutboxEntry> {
        return sqlDelightDao.getEligible(cloudUserId, now)
    }

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) {
        sqlDelightDao.updateBaseRevision(mutationId, baseRevision)
    }

    override suspend fun delete(mutationId: String) {
        sqlDelightDao.delete(mutationId)
    }

    override suspend fun recordFailure(
        mutationId: String,
        nextAttemptAt: String,
        error: String,
    ) {
        sqlDelightDao.recordFailure(mutationId, nextAttemptAt, error)
    }

    override suspend fun coalesce(entityType: String, entityId: String, entry: SyncOutboxEntry) {
        sqlDelightDao.coalesce(entityType, entityId, entry)
    }

    override suspend fun clearAllData() {
        sqlDelightDao.deleteAll()
    }
}
