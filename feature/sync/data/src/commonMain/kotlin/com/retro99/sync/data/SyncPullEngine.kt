package com.retro99.sync.data

import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import com.retro99.sync.domain.SyncPhase
import com.retro99.sync.domain.SyncPhaseReporter

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
        reportPhase: SyncPhaseReporter = { _, _, _ -> },
        fetchAndApply: suspend (
            cursor: String?,
            limit: Int,
            reportApplying: () -> Unit,
        ) -> SyncPullPage,
    ): SyncPullSummary {
        var cursor = syncCheckpointDatabase
            .getCheckpoint(destinationId, remoteAccountId)
            ?.cursor
        var pulledChangeCount = 0
        var pageCount = 0

        do {
            reportPhase(SyncPhase.PULLING, pulledChangeCount, null)
            val page = fetchAndApply(
                cursor,
                limit,
                { reportPhase(SyncPhase.APPLYING, pulledChangeCount, null) },
            )
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
            if (page.changeCount > 0) {
                reportPhase(SyncPhase.APPLYING, pulledChangeCount, null)
            }
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
