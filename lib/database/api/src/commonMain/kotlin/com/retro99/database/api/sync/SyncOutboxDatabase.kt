package com.retro99.database.api.sync

import com.retro99.database.api.DataClearable

interface SyncOutboxDatabase : DataClearable {

    suspend fun enqueue(entry: SyncOutboxEntry)

    suspend fun getPending(): List<SyncOutboxEntry>

    suspend fun delete(mutationId: String)

    override suspend fun clearAllData()
}
