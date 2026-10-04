package com.retro99.database.api.sync

import com.retro99.database.api.DataClearable

interface SyncOutboxDatabase : DataClearable {

    suspend fun enqueue(entry: SyncOutboxEntry)

    suspend fun bindUnassignedMutations(cloudUserId: String)

    suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry>

    /** Read-only preview, including local changes awaiting their first account binding. */
    suspend fun getPendingIncludingUnassigned(cloudUserId: String): List<SyncOutboxEntry> = getPending(cloudUserId)

    suspend fun getEligible(
        cloudUserId: String,
        now: String,
    ): List<SyncOutboxEntry> {
        return getPending(cloudUserId)
    }

    suspend fun updateBaseRevision(mutationId: String, baseRevision: Long)

    suspend fun markDispatched(mutationId: String)

    suspend fun markConflict(mutationId: String, error: String)

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
