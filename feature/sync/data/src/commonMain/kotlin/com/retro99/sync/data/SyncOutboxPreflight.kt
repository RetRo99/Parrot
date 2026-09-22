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
 * layer only applies the common state contract and a destination capability
 * predicate; it never deletes or rewrites mutations that are not candidates.
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
        include: (SyncOutboxEntry) -> Boolean = { true },
    ): List<SyncOutboxEntry> {
        require(maxEntries >= 0) { "Maximum outbox candidates cannot be negative" }

        return database.getEligible(remoteAccountId, now)
            .asSequence()
            .filter { entry ->
                entry.state == SyncOutboxEntry.STATE_PENDING ||
                    entry.state == SyncOutboxEntry.STATE_DISPATCHED
            }
            .filter(include)
            .take(maxEntries)
            .toList()
    }

    suspend fun pendingCount(remoteAccountId: String): Int {
        return database.getPending(remoteAccountId).size
    }
}
