package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncPhase
import com.retro99.sync.domain.SyncPhaseReporter
import com.retro99.sync.domain.SyncResult
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Shared pull, push, and continuation ordering for one bounded destination
 * pass. A destination supplies only transport mapping and local application
 * callbacks.
 */
@Single
class SyncBoundedPass(
    @Provided private val syncPullEngine: SyncPullEngine,
) {
    suspend fun execute(
        destinationId: String,
        remoteAccountId: String,
        batchSize: Int,
        selectEntries: suspend () -> List<SyncOutboxEntry>,
        refreshProgressEntries: suspend (entries: List<SyncOutboxEntry>) -> Unit = {},
        pushProgressEntries: suspend (entries: List<SyncOutboxEntry>) -> Int,
        pushLibraryMutationEntries: suspend (
            entries: List<SyncOutboxEntry>,
            cursor: String?,
        ) -> Int,
        fetchAndApply: suspend (
            cursor: String?,
            limit: Int,
            reportApplying: () -> Unit,
        ) -> SyncPullPage,
        pendingMutationCount: suspend () -> Int,
        pullEnabled: Boolean = true,
        reportPhase: SyncPhaseReporter = { _, _, _ -> },
    ): SyncResult.Completed {
        require(batchSize > 0) { "Sync batch size must be positive" }

        val initialPull = if (pullEnabled) {
            syncPullEngine.pullUntilCaughtUp(
                destinationId = destinationId,
                remoteAccountId = remoteAccountId,
                limit = batchSize,
                reportPhase = reportPhase,
                fetchAndApply = fetchAndApply,
            )
        } else {
            SyncPullSummary(
                cursor = null,
                pulledChangeCount = 0,
                pageCount = 0,
            )
        }
        reportPhase(SyncPhase.PREPARING, 0, null)
        val entries = selectEntries().take(batchSize)
        val pushedCount = if (entries.isEmpty()) {
            0
        } else {
            reportPhase(SyncPhase.PULLING, 0, entries.size)
            refreshProgressEntries(entries)
            val progressEntries = entries.filter { entry ->
                entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
            }
            val libraryMutationEntries = entries.filter { entry ->
                entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
            }
            val progressCount = if (progressEntries.isEmpty()) {
                0
            } else {
                reportPhase(SyncPhase.UPLOADING_CHANGES, 0, entries.size)
                pushProgressEntries(progressEntries)
            }
            val libraryMutationCount = if (libraryMutationEntries.isEmpty()) {
                0
            } else {
                reportPhase(
                    SyncPhase.UPLOADING_CHANGES,
                    progressCount,
                    entries.size,
                )
                pushLibraryMutationEntries(libraryMutationEntries, initialPull.cursor)
            }
            progressCount + libraryMutationCount
        }
        val afterPushPull = if (entries.isNotEmpty() && pullEnabled) {
            syncPullEngine.pullUntilCaughtUp(
                destinationId = destinationId,
                remoteAccountId = remoteAccountId,
                limit = batchSize,
                reportPhase = reportPhase,
                fetchAndApply = fetchAndApply,
            )
        } else {
            null
        }

        reportPhase(SyncPhase.FINALIZING, pushedCount, entries.size)
        return SyncResult.Completed(
            pushedMutationCount = pushedCount,
            pulledChangeCount = initialPull.pulledChangeCount +
                (afterPushPull?.pulledChangeCount ?: 0),
            pendingMutationCount = pendingMutationCount(),
        )
    }
}
