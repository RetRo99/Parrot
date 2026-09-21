package com.retro99.database.api.sync

import com.retro99.database.api.DataClearable

interface SyncPendingChangesDatabase : DataClearable {
    suspend fun upsert(change: SyncPendingChange)

    suspend fun getPending(cloudUserId: String): List<SyncPendingChange>

    suspend fun delete(cloudUserId: String, changeId: Long)

    override suspend fun clearAllData()
}
