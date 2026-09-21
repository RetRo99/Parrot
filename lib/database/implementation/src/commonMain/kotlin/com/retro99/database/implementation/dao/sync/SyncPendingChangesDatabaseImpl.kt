package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncPendingChange
import com.retro99.database.api.sync.SyncPendingChangesDatabase

internal class SyncPendingChangesDatabaseImpl(
    private val sqlDelightDao: SyncPendingChangesSqlDelightDao,
) : SyncPendingChangesDatabase {
    override suspend fun upsert(change: SyncPendingChange) {
        sqlDelightDao.upsert(change)
    }

    override suspend fun getPending(cloudUserId: String): List<SyncPendingChange> {
        return sqlDelightDao.getPending(cloudUserId)
    }

    override suspend fun delete(cloudUserId: String, changeId: Long) {
        sqlDelightDao.delete(cloudUserId, changeId)
    }

    override suspend fun clearAllData() {
        sqlDelightDao.deleteAll()
    }
}
