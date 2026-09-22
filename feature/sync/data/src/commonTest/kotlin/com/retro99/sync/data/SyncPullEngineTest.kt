package com.retro99.sync.data

import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncPullEngineTest {

    @Test
    fun resumesFromDurableCursorAndPersistsEachPageBoundary() = runTest {
        val checkpoints = RecordingCheckpointDatabase(
            SyncCheckpoint(
                destinationId = "parrot-cloud",
                remoteAccountId = "account",
                cursor = "7",
                updatedAt = "old",
            ),
        )
        val requestedCursors = mutableListOf<String?>()
        val engine = SyncPullEngine(checkpoints)

        val summary = engine.pullUntilCaughtUp(
            destinationId = "parrot-cloud",
            remoteAccountId = "account",
            limit = 50,
        ) { cursor, _ ->
            requestedCursors += cursor
            when (cursor) {
                "7" -> SyncPullPage(changeCount = 2, nextCursor = "8", hasMore = true)
                else -> SyncPullPage(changeCount = 1, nextCursor = "9", hasMore = false)
            }
        }

        assertEquals(listOf<String?>("7", "8"), requestedCursors)
        assertEquals(3, summary.pulledChangeCount)
        assertEquals(2, summary.pageCount)
        assertEquals("9", summary.cursor)
        assertEquals(listOf<String?>("8", "9"), checkpoints.saved.map { it.cursor })
    }
}

private class RecordingCheckpointDatabase(
    private var current: SyncCheckpoint?,
) : SyncCheckpointDatabase {
    val saved = mutableListOf<SyncCheckpoint>()

    override suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint? = current

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        current = checkpoint
        saved += checkpoint
    }

    override suspend fun clearAllData() = Unit
}
