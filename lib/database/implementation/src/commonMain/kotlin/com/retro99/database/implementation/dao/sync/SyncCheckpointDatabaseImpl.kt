package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase

internal class SyncCheckpointDatabaseImpl(
    private val sqlDelightDao: SyncCheckpointSqlDelightDao,
) : SyncCheckpointDatabase {
    override suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint? {
        return sqlDelightDao.getCheckpoint(destinationId, remoteAccountId)
    }

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        sqlDelightDao.saveCheckpoint(checkpoint)
    }

    override suspend fun clearAllData() {
        sqlDelightDao.clearAll()
    }
}
