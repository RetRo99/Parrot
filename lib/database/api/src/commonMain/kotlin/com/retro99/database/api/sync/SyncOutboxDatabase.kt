package com.retro99.database.api.sync

import com.retro99.database.api.DataClearable

interface SyncOutboxDatabase : DataClearable {

    suspend fun enqueue(entry: SyncOutboxEntry)

    suspend fun bindUnassignedMutations(cloudUserId: String)

    suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry>

    suspend fun getEligible(
        cloudUserId: String,
        now: String,
    ): List<SyncOutboxEntry> {
        return getPending(cloudUserId)
    }

    suspend fun updateBaseRevision(mutationId: String, baseRevision: Long)

    suspend fun delete(mutationId: String)

    suspend fun deleteByEntityType(entityType: String)

    suspend fun recordFailure(
        mutationId: String,
        nextAttemptAt: String,
        error: String,
    )

    suspend fun coalesce(entityType: String, entityId: String, entry: SyncOutboxEntry)

    override suspend fun clearAllData()
}
