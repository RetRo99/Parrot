package com.retro99.database.api.sync

import com.retro99.database.api.DataClearable

interface SyncOutboxDatabase : DataClearable {

    suspend fun enqueue(entry: SyncOutboxEntry)

    suspend fun bindUnassignedMutations(cloudUserId: String)

    suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry>

    suspend fun updateBaseRevision(mutationId: String, baseRevision: Long)

    suspend fun delete(mutationId: String)

    override suspend fun clearAllData()
}
