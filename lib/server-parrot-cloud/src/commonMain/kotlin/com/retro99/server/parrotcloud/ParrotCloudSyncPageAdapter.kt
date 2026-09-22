package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.data.SyncPullPage
import com.retro99.sync.domain.LegacySyncTransport
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.RemoteProgressSnapshot
import com.retro99.sync.domain.SyncChange
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Combines Parrot's legacy and progress change feeds into the shared page
 * contract. Payload decoding and local reconciliation stay with the caller.
 */
@Single
class ParrotCloudSyncPageAdapter(
    @Provided private val legacyTransport: LegacySyncTransport,
    @Provided private val progressTransport: ProgressSyncTransport,
) {
    suspend fun fetchPage(
        cursor: String,
        limit: Int,
        onLegacyChange: suspend (SyncChange) -> Unit,
        onProgressChange: suspend (RemoteProgressSnapshot) -> Unit,
    ): SyncPullPage {
        val legacyResponse = legacyTransport.pull(
            cursor = cursor,
            limit = limit,
        )
        val legacyChanges = legacyResponse.changes.filter { change ->
            change.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
        }
        for (change in legacyChanges) {
            onLegacyChange(change)
        }

        val progressPage = progressTransport.fetchChanges(
            cursor = cursor,
            limit = limit,
        )
        for (change in progressPage.changes) {
            onProgressChange(change)
        }

        val currentCursor = cursor.toLongOrNull() ?: 0L
        val nextCursor = maxOf(
            legacyResponse.nextCursor?.toLongOrNull() ?: currentCursor,
            progressPage.nextCursor?.toLongOrNull() ?: currentCursor,
        )
        return SyncPullPage(
            changeCount = legacyChanges.size + progressPage.changes.size,
            nextCursor = nextCursor.toString(),
            hasMore = legacyResponse.hasMore || progressPage.hasMore,
        )
    }
}
