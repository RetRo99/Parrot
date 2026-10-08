package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Shared account-scoped preparation and candidate selection for one sync pass.
 *
 * The database remains the source of retry timing and account ownership. This
 * layer prunes superseded reading checkpoints before applying the batch limit.
 * Unsupported mutations and unresolved preserved conflicts remain untouched.
 */
@Single
class SyncOutboxPreflight(
    @Provided private val database: SyncOutboxDatabase,
) {
    suspend fun bindUnassignedMutations(remoteAccountId: String) {
        database.bindUnassignedMutations(remoteAccountId)
    }

    suspend fun selectEligible(
        remoteAccountId: String,
        maxEntries: Int,
        now: String = Clock.System.now().toString(),
        capability: SyncOutboxCapability = SyncOutboxCapability.All,
    ): List<SyncOutboxEntry> {
        require(maxEntries >= 0) { "Maximum outbox candidates cannot be negative" }
        if (maxEntries == 0) return emptyList()

        // Select the latest reading before applying the batch limit. Sending every old
        // checkpoint (especially in a one-item batch) leaves the server many pages behind.
        val progress = database.getPending(remoteAccountId).filter { entry ->
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION &&
                entry.state in setOf(SyncOutboxEntry.STATE_PENDING, SyncOutboxEntry.STATE_DISPATCHED) &&
                capability.supports(entry)
        }
        progress.groupBy { it.entityId }.values.forEach { writes ->
            val newest = writes.withIndex().maxWithOrNull(
                compareBy({ it.value.localGeneration }, { it.index }),
            )?.value ?: return@forEach
            writes.filter { it.mutationId != newest.mutationId }.forEach { old ->
                database.delete(old.mutationId)
            }
        }

        return database.getEligible(remoteAccountId, now)
            .asSequence()
            .filter { entry ->
                entry.state == SyncOutboxEntry.STATE_PENDING ||
                    entry.state == SyncOutboxEntry.STATE_DISPATCHED
            }
            .filter { entry -> capability.supports(entry) }
            .take(maxEntries)
            .toList()
    }

    suspend fun pendingCount(
        remoteAccountId: String,
        capability: SyncOutboxCapability = SyncOutboxCapability.All,
    ): Int {
        return database.getPending(remoteAccountId)
            .count { entry -> capability.supports(entry) }
    }
}
