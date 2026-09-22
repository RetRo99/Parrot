package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxEntry
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
        pushProgressEntries: suspend (entries: List<SyncOutboxEntry>) -> Int,
        pushLegacyEntries: suspend (
            entries: List<SyncOutboxEntry>,
            cursor: String?,
        ) -> Int,
        fetchAndApply: suspend (cursor: String?, limit: Int) -> SyncPullPage,
        pendingMutationCount: suspend () -> Int,
    ): SyncResult.Completed {
        require(batchSize > 0) { "Sync batch size must be positive" }

        val initialPull = syncPullEngine.pullUntilCaughtUp(
            destinationId = destinationId,
            remoteAccountId = remoteAccountId,
            limit = batchSize,
            fetchAndApply = fetchAndApply,
        )
        val entries = selectEntries().take(batchSize)
        val pushedCount = if (entries.isEmpty()) {
            0
        } else {
            val progressEntries = entries.filter { entry ->
                entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
            }
            val legacyEntries = entries.filter { entry ->
                entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
            }
            val progressCount = if (progressEntries.isEmpty()) {
                0
            } else {
                pushProgressEntries(progressEntries)
            }
            val legacyCount = if (legacyEntries.isEmpty()) {
                0
            } else {
                pushLegacyEntries(legacyEntries, initialPull.cursor)
            }
            progressCount + legacyCount
        }
        val afterPushPull = if (entries.isNotEmpty()) {
            syncPullEngine.pullUntilCaughtUp(
                destinationId = destinationId,
                remoteAccountId = remoteAccountId,
                limit = batchSize,
                fetchAndApply = fetchAndApply,
            )
        } else {
            null
        }

        return SyncResult.Completed(
            pushedMutationCount = pushedCount,
            pulledChangeCount = initialPull.pulledChangeCount +
                (afterPushPull?.pulledChangeCount ?: 0),
            pendingMutationCount = pendingMutationCount(),
        )
    }
}
