package com.retro99.sync.data

import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncBoundedPassTest {
    @Test
    fun ordersPullSelectionPushPullAndPendingCount() = runTest {
        val checkpoints = BoundedRecordingCheckpointDatabase()
        val events = mutableListOf<String>()
        val progressEntityTypes = mutableListOf<String>()
        val legacyEntityTypes = mutableListOf<String>()
        var fetchCount = 0
        val coordinator = SyncBoundedPass(SyncPullEngine(checkpoints))

        val result = coordinator.execute(
            destinationId = "parrot-cloud",
            remoteAccountId = "account",
            batchSize = 50,
            selectEntries = {
                events += "select"
                listOf(
                    testEntry(
                        mutationId = "progress",
                        entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                    ),
                    testEntry(
                        mutationId = "legacy",
                        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    ),
                )
            },
            pushProgressEntries = { entries ->
                progressEntityTypes += entries.map { entry -> entry.entityType }
                events += "progress:${entries.size}"
                1
            },
            pushLegacyEntries = { entries, cursor ->
                legacyEntityTypes += entries.map { entry -> entry.entityType }
                events += "legacy:${entries.size}:$cursor"
                2
            },
            fetchAndApply = { cursor, _ ->
                events += "pull:$cursor"
                fetchCount++
                if (fetchCount == 1) {
                    SyncPullPage(changeCount = 2, nextCursor = "1", hasMore = false)
                } else {
                    SyncPullPage(changeCount = 3, nextCursor = "2", hasMore = false)
                }
            },
            pendingMutationCount = {
                events += "pending"
                4
            },
        )
        assertEquals(
            listOf(SyncOutboxEntry.ENTITY_TYPE_READING_POSITION),
            progressEntityTypes,
        )
        assertEquals(
            listOf(SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK),
            legacyEntityTypes,
        )

        assertEquals(
            listOf("pull:null", "select", "progress:1", "legacy:1:1", "pull:1", "pending"),
            events,
        )
        assertEquals(
            SyncResult.Completed(
                pushedMutationCount = 3,
                pulledChangeCount = 5,
                pendingMutationCount = 4,
            ),
            result,
        )
    }

    @Test
    fun skipsSecondPullWhenSelectionIsEmpty() = runTest {
        val checkpoints = BoundedRecordingCheckpointDatabase()
        val events = mutableListOf<String>()
        val coordinator = SyncBoundedPass(SyncPullEngine(checkpoints))

        val result = coordinator.execute(
            destinationId = "parrot-cloud",
            remoteAccountId = "account",
            batchSize = 50,
            selectEntries = {
                events += "select"
                emptyList()
            },
            pushProgressEntries = { _ ->
                events += "push"
                0
            },
            pushLegacyEntries = { _, _ ->
                events += "legacy"
                0
            },
            fetchAndApply = { cursor, _ ->
                events += "pull:$cursor"
                SyncPullPage(changeCount = 0, nextCursor = "0", hasMore = false)
            },
            pendingMutationCount = {
                events += "pending"
                0
            },
        )

        assertEquals(listOf("pull:null", "select", "pending"), events)
        assertEquals(0, result.pushedMutationCount)
        assertEquals(0, result.pulledChangeCount)
    }
}

private class BoundedRecordingCheckpointDatabase : SyncCheckpointDatabase {
    private var current: SyncCheckpoint? = null

    override suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint? = current

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        current = checkpoint
    }

    override suspend fun clearAllData() = Unit
}

private fun testEntry(
    mutationId: String,
    entityType: String,
): SyncOutboxEntry {
    return SyncOutboxEntry(
        mutationId = mutationId,
        cloudUserId = "account",
        entityType = entityType,
        entityId = "book",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        baseRevision = null,
        createdAt = "2026-09-22T00:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )
}
