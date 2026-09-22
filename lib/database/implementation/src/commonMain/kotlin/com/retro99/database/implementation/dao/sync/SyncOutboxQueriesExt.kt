package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.Sync_outbox
import com.retro99.database.implementation.SyncOutboxQueries

internal fun SyncOutboxQueries.enqueue(mutation: SyncOutboxEntry) {
    deletePendingMutationsForEntity(
        cloud_user_id = mutation.cloudUserId,
        entity_type = mutation.entityType,
        entity_id = mutation.entityId,
    )
    enqueueMutation(
        mutation_id = mutation.mutationId,
        cloud_user_id = mutation.cloudUserId,
        entity_type = mutation.entityType,
        entity_id = mutation.entityId,
        operation = mutation.operation,
        payload = mutation.payload,
        base_revision = mutation.baseRevision,
        created_at = mutation.createdAt,
        attempt_count = mutation.attemptCount.toLong(),
        next_attempt_at = mutation.nextAttemptAt,
        last_error = mutation.lastError,
    )
}

internal fun Sync_outbox.toEntry(): SyncOutboxEntry {
    return SyncOutboxEntry(
        mutationId = mutation_id,
        cloudUserId = cloud_user_id,
        entityType = entity_type,
        entityId = entity_id,
        operation = operation,
        payload = payload,
        baseRevision = base_revision,
        createdAt = created_at,
        attemptCount = attempt_count.toInt(),
        nextAttemptAt = next_attempt_at,
        lastError = last_error,
    )
}
