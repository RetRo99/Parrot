package com.retro99.reader.domain.fakes

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry

class FakeSyncOutboxDatabase(
    val entries: MutableList<SyncOutboxEntry> = mutableListOf(),
) : SyncOutboxDatabase {
    override suspend fun enqueue(entry: SyncOutboxEntry) { entries += entry }
    override suspend fun getPending(cloudUserId: String) = entries.filter { it.cloudUserId == cloudUserId }
    override suspend fun getPendingIncludingUnassigned(cloudUserId: String) =
        entries.filter { it.cloudUserId == null || it.cloudUserId == cloudUserId }
    override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit
    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit
    override suspend fun markDispatched(mutationId: String) = Unit
    override suspend fun markConflict(mutationId: String, error: String) = Unit
    override suspend fun delete(mutationId: String) { entries.removeAll { it.mutationId == mutationId } }
    override suspend fun deleteByEntityType(entityType: String) { entries.removeAll { it.entityType == entityType } }
    override suspend fun recordFailure(mutationId: String, nextAttemptAt: String, error: String) = Unit
    override suspend fun coalesce(entityType: String, entityId: String, entry: SyncOutboxEntry) = enqueue(entry)
    override suspend fun clearAllData() { entries.clear() }
}
