package com.retro99.server.parrotcloud

import com.retro99.sync.domain.LegacySyncTransport
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.ProgressTransportCapabilities
import com.retro99.sync.domain.RemoteProgressSnapshot
import com.retro99.sync.domain.SyncChange
import com.retro99.sync.domain.SyncMutationRequest
import com.retro99.sync.domain.SyncMutationResponse
import com.retro99.sync.domain.SyncChangePage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ParrotCloudSyncPageAdapterTest {
    @Test
    fun combinesFeedsFiltersProgressFromLegacyAndMergesCursor() = runTest {
        val events = mutableListOf<String>()
        val legacyTransport = RecordingLegacyTransport(events).apply {
            response = SyncChangePage(
                changes = listOf(
                    SyncChange(
                        entityType = "library_book",
                        payload = "book",
                        revision = 1L,
                    ),
                    SyncChange(
                        entityType = "reading_position",
                        payload = "legacy-progress",
                        revision = 2L,
                    ),
                ),
                nextCursor = "8",
                hasMore = true,
            )
        }
        val progressTransport = RecordingProgressTransport(events).apply {
            response = ProgressChangePage(
                changes = listOf(testRemoteProgress()),
                nextCursor = "10",
                hasMore = false,
            )
        }
        val adapter = ParrotCloudSyncPageAdapter(legacyTransport, progressTransport)
        val legacyChanges = mutableListOf<String>()
        val progressChanges = mutableListOf<String>()

        val page = adapter.fetchPage(
            cursor = "7",
            limit = 50,
            onLegacyChange = { change ->
                events += "legacy-change"
                legacyChanges += change.payload
            },
            onProgressChange = { remote ->
                events += "progress-change"
                progressChanges += remote.remoteBookId
            },
        )

        assertEquals(
            listOf("legacy-pull", "legacy-change", "progress-pull", "progress-change"),
            events,
        )
        assertEquals(listOf("book"), legacyChanges)
        assertEquals(listOf("remote-book"), progressChanges)
        assertEquals(2, page.changeCount)
        assertEquals("10", page.nextCursor)
        assertEquals(true, page.hasMore)
        assertEquals("7", legacyTransport.requestedCursor)
        assertEquals("7", progressTransport.requestedCursor)
    }

    @Test
    fun missingFeedCursorKeepsCurrentCursor() = runTest {
        val adapter = ParrotCloudSyncPageAdapter(
            RecordingLegacyTransport(mutableListOf()).apply {
                response = SyncChangePage(emptyList(), nextCursor = null, hasMore = false)
            },
            RecordingProgressTransport(mutableListOf()).apply {
                response = ProgressChangePage(emptyList(), nextCursor = null, hasMore = false)
            },
        )

        val page = adapter.fetchPage(
            cursor = "7",
            limit = 50,
            onLegacyChange = {},
            onProgressChange = {},
        )

        assertEquals("7", page.nextCursor)
    }
}

private class RecordingLegacyTransport(
    private val events: MutableList<String>,
) : LegacySyncTransport {
    var requestedCursor: String? = null
    var response = SyncChangePage(emptyList(), nextCursor = null, hasMore = false)

    override suspend fun push(
        mutations: List<SyncMutationRequest>,
        cursor: String?,
    ): List<SyncMutationResponse> = emptyList()

    override suspend fun pull(cursor: String?, limit: Int): SyncChangePage {
        requestedCursor = cursor
        events += "legacy-pull"
        return response
    }
}

private class RecordingProgressTransport(
    private val events: MutableList<String>,
) : ProgressSyncTransport {
    var requestedCursor: String? = null
    var response = ProgressChangePage(emptyList(), nextCursor = null, hasMore = false)

    override val capabilities = ProgressTransportCapabilities(
        supportsBatching = true,
        maxBatchSize = 50,
        supportsConditionalWrites = true,
        supportsIdempotency = true,
        supportsChangeFeed = true,
        supportsRemoteFetch = true,
    )

    override suspend fun fetchProgress(
        remoteBookIds: Set<String>,
    ): Map<String, RemoteProgressSnapshot> = emptyMap()

    override suspend fun fetchChanges(
        cursor: String?,
        limit: Int,
    ): ProgressChangePage {
        requestedCursor = cursor
        events += "progress-pull"
        return response
    }

    override suspend fun pushProgress(
        mutations: List<ProgressMutation>,
    ): List<ProgressPushResult> = emptyList()
}

private fun testRemoteProgress(): RemoteProgressSnapshot {
    return RemoteProgressSnapshot(
        entityId = "remote-progress",
        remoteBookId = "remote-book",
        libraryBookId = null,
        kind = ProgressKind.EBOOK,
        snapshot = ProgressSnapshot(
            timestamp = null,
            createdAt = null,
            updatedAt = null,
            locator = null,
            audioTimestampMs = null,
            chapterIndex = null,
            progression = null,
            totalChapters = null,
            totalDurationMs = null,
            totalProgression = null,
            position = null,
        ),
        version = "1",
        observedAt = null,
    )
}
