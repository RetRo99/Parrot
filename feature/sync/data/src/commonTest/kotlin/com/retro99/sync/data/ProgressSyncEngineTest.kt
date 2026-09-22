package com.retro99.sync.data

import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.ProgressTransportCapabilities
import com.retro99.sync.domain.RemoteProgressSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressSyncEngineTest {

    @Test
    fun acceptedMutationUpdatesBaselineAndDeletesOnlyItsOutboxEntry() = runTest {
        val mutation = outboxEntry(
            mutationId = "mutation-1",
            entityId = "book-1",
            localGeneration = 7L,
        )
        val outbox = RecordingOutboxDatabase(listOf(mutation))
        val positions = RecordingPositionDatabase()
        val transport = RecordingTransport(
            pushResults = listOf(
                ProgressPushResult.Accepted(
                    mutationId = mutation.mutationId,
                    version = "42",
                ),
            ),
        )
        val engine = ProgressSyncEngine(outbox, positions)

        val summary = engine.push(
            entries = listOf(mutation),
            transport = transport,
            codec = ProgressOutboxCodec { entry -> entry.toProgressMutation() },
        )

        assertEquals(1, summary.acknowledgedCount)
        assertEquals(listOf(mutation.mutationId), outbox.dispatchedIds)
        assertEquals(listOf(mutation.mutationId), outbox.deletedIds)
        assertEquals(RevisionUpdate("book-1", 42L, 7L), positions.revisionUpdates.single())
    }

    @Test
    fun omittedResultRemainsDispatchedAndUnresolved() = runTest {
        val mutation = outboxEntry(mutationId = "mutation-omitted")
        val outbox = RecordingOutboxDatabase(listOf(mutation))
        val engine = ProgressSyncEngine(outbox, RecordingPositionDatabase())

        val summary = engine.push(
            entries = listOf(mutation),
            transport = RecordingTransport(pushResults = emptyList()),
            codec = ProgressOutboxCodec { entry -> entry.toProgressMutation() },
        )

        assertEquals(1, summary.unresolvedCount)
        assertTrue(outbox.deletedIds.isEmpty())
        assertTrue(outbox.failureIds.isEmpty())
    }

    @Test
    fun dirtyPullStoresRemoteBaselineWithoutReplacingLocalPosition() = runTest {
        val pending = outboxEntry(
            mutationId = "pending-1",
            entityId = "book-1",
        )
        val outbox = RecordingOutboxDatabase(listOf(pending))
        val positions = RecordingPositionDatabase()
        val engine = ProgressSyncEngine(outbox, positions)

        val outcome = engine.applyRemote(
            remote = remoteSnapshot(),
            accountId = "account-1",
        )

        assertEquals(ProgressPullOutcome.PreservedLocalProgress, outcome)
        assertEquals(1, positions.remotePositions.size)
        assertTrue(positions.localPositions.isEmpty())
        assertTrue(positions.deletedRemoteBookIds.isEmpty())
    }

    @Test
    fun cleanPullAppliesRemotePositionAndRemovesStaleBaseline() = runTest {
        val outbox = RecordingOutboxDatabase(emptyList())
        val positions = RecordingPositionDatabase()
        val engine = ProgressSyncEngine(outbox, positions)

        val outcome = engine.applyRemote(
            remote = remoteSnapshot(),
            accountId = "account-1",
        )

        assertEquals(ProgressPullOutcome.AppliedToLocal, outcome)
        assertEquals("book-1", positions.localPositions.single().bookUuid)
        assertEquals(listOf("book-1"), positions.deletedRemoteBookIds)
    }

    @Test
    fun refreshRemoteFetchesSelectedBooksAndPreservesLocalProgress() = runTest {
        val mutation = outboxEntry(mutationId = "pending-refresh")
        val outbox = RecordingOutboxDatabase(listOf(mutation))
        val positions = RecordingPositionDatabase()
        val transport = RecordingTransport(
            fetchResults = mapOf("remote-book-1" to remoteSnapshot()),
        )
        val engine = ProgressSyncEngine(outbox, positions)

        val refreshed = engine.refreshRemote(
            entries = listOf(mutation),
            accountId = "account-1",
            transport = transport,
            codec = ProgressOutboxCodec { entry -> entry.toProgressMutation() },
        )

        assertEquals(1, refreshed)
        assertEquals(setOf("remote-book-1"), transport.fetchedBookIds)
        assertEquals(1, positions.remotePositions.size)
        assertTrue(positions.localPositions.isEmpty())
    }

    private fun outboxEntry(
        mutationId: String,
        entityId: String = "book-1",
        localGeneration: Long = 1L,
    ) = SyncOutboxEntry(
        mutationId = mutationId,
        cloudUserId = "account-1",
        entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
        entityId = entityId,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        baseRevision = 3L,
        createdAt = "2026-09-22T10:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
        localGeneration = localGeneration,
    )

    private fun SyncOutboxEntry.toProgressMutation() = ProgressMutation(
        mutationId = mutationId,
        entityId = entityId,
        remoteBookId = "remote-book-1",
        libraryBookId = "book-1",
        kind = ProgressKind.EBOOK,
        snapshot = emptySnapshot(),
        baseVersion = baseRevision?.toString(),
        observedAt = createdAt,
    )

    private fun remoteSnapshot() = RemoteProgressSnapshot(
        entityId = "book-1",
        remoteBookId = "remote-book-1",
        libraryBookId = "book-1",
        kind = ProgressKind.EBOOK,
        snapshot = emptySnapshot(),
        version = "9",
        observedAt = "2026-09-22T10:01:00Z",
    )

    private fun emptySnapshot() = ProgressSnapshot(
        timestamp = 1L,
        createdAt = "2026-09-22T10:00:00Z",
        updatedAt = "2026-09-22T10:00:00Z",
        locator = null,
        audioTimestampMs = null,
        chapterIndex = null,
        progression = null,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = null,
        position = null,
    )
}

private class RecordingTransport(
    private val pushResults: List<ProgressPushResult> = emptyList(),
    private val fetchResults: Map<String, RemoteProgressSnapshot> = emptyMap(),
) : ProgressSyncTransport {
    var fetchedBookIds: Set<String> = emptySet()

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
    ): Map<String, RemoteProgressSnapshot> {
        fetchedBookIds = remoteBookIds
        return fetchResults
    }

    override suspend fun fetchChanges(cursor: String?, limit: Int): ProgressChangePage =
        ProgressChangePage(emptyList(), cursor, false)

    override suspend fun pushProgress(
        mutations: List<ProgressMutation>,
    ): List<ProgressPushResult> = pushResults
}

private class RecordingOutboxDatabase(
    initialEntries: List<SyncOutboxEntry>,
) : SyncOutboxDatabase {
    private val entries = initialEntries.toMutableList()
    val dispatchedIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    val failureIds = mutableListOf<String>()

    override suspend fun enqueue(entry: SyncOutboxEntry) {
        entries += entry
    }

    override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = entries.toList()

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) {
        dispatchedIds += mutationId
    }

    override suspend fun markConflict(mutationId: String, error: String) = Unit

    override suspend fun delete(mutationId: String) {
        deletedIds += mutationId
        entries.removeAll { entry -> entry.mutationId == mutationId }
    }

    override suspend fun deleteByEntityType(entityType: String) = Unit

    override suspend fun recordFailure(
        mutationId: String,
        nextAttemptAt: String,
        error: String,
    ) {
        failureIds += mutationId
    }

    override suspend fun coalesce(entityType: String, entityId: String, entry: SyncOutboxEntry) = Unit

    override suspend fun clearAllData() = Unit
}

private data class RevisionUpdate(
    val bookUuid: String,
    val revision: Long,
    val expectedGeneration: Long?,
)

private class RecordingPositionDatabase : PositionDatabase {
    val localPositions = mutableListOf<PositionEntity>()
    val remotePositions = mutableListOf<PositionEntity>()
    val deletedRemoteBookIds = mutableListOf<String>()
    val revisionUpdates = mutableListOf<RevisionUpdate>()

    override suspend fun upsertPosition(position: PositionEntity) {
        localPositions += position
    }

    override suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    ) = Unit

    override suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) {
        revisionUpdates += RevisionUpdate(bookUuid, remoteRevision, expectedLocalGeneration)
    }

    override suspend fun upsertRemotePosition(position: PositionEntity) {
        remotePositions += position
    }

    override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? = null

    override suspend fun deleteRemotePosition(bookUuid: String) {
        deletedRemoteBookIds += bookUuid
    }

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? = null

    override suspend fun getAllPositions(): List<PositionEntity> = localPositions.toList()

    override suspend fun deletePosition(bookUuid: String) = Unit

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = emptyFlow()

    override fun observeAllPositions(): Flow<List<PositionEntity>> = emptyFlow()

    override suspend fun clearAllData() = Unit
}
