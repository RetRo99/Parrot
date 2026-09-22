package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxEntry

/**
 * Destination capability declaration used by shared outbox preflight.
 * Unsupported mutations remain durable for a later capable destination.
 */
data class SyncOutboxCapability(
    val unsupportedEntityTypes: Set<String> = emptySet(),
) {
    fun supports(entry: SyncOutboxEntry): Boolean {
        return entry.entityType !in unsupportedEntityTypes
    }

    companion object {
        val All = SyncOutboxCapability()
    }
}
