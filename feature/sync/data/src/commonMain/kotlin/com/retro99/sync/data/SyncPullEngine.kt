package com.retro99.sync.data

import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

/**
 * Shared durable cursor loop for change-feed based synchronization.
 *
 * The page callback owns transport mapping and local reconciliation for one
 * page. This coordinator owns checkpoint recovery, continuation, and durable
 * advancement after each successfully applied page.
 */
@Single
class SyncPullEngine(
    @Provided private val syncCheckpointDatabase: SyncCheckpointDatabase,
) {
    suspend fun pullUntilCaughtUp(
        destinationId: String,
        remoteAccountId: String,
        limit: Int,
        fetchAndApply: suspend (cursor: String?, limit: Int) -> SyncPullPage,
    ): SyncPullSummary {
        var cursor = syncCheckpointDatabase
            .getCheckpoint(destinationId, remoteAccountId)
            ?.cursor
        var pulledChangeCount = 0
        var pageCount = 0

        do {
            val page = fetchAndApply(cursor, limit)
            pulledChangeCount += page.changeCount
            pageCount++
            cursor = page.nextCursor ?: cursor
            syncCheckpointDatabase.saveCheckpoint(
                SyncCheckpoint(
                    destinationId = destinationId,
                    remoteAccountId = remoteAccountId,
                    cursor = cursor,
                    updatedAt = Clock.System.now().toString(),
                ),
            )
        } while (page.hasMore)

        return SyncPullSummary(
            cursor = cursor,
            pulledChangeCount = pulledChangeCount,
            pageCount = pageCount,
        )
    }
}

data class SyncPullPage(
    val changeCount: Int,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class SyncPullSummary(
    val cursor: String?,
    val pulledChangeCount: Int,
    val pageCount: Int,
)
