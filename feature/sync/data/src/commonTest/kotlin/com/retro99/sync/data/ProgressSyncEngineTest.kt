package com.retro99.sync.data

import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.links.LinkedCopyWriteEntity
import com.retro99.database.api.links.LinkedCopyWritesDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import com.retro99.sync.domain.ProgressSourceDevice
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
        val engine = ProgressSyncEngine(outbox, positions, RecordingWrites())

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
        val engine = ProgressSyncEngine(outbox, RecordingPositionDatabase(), RecordingWrites())

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
    fun `a superseded selection is not dispatched or sent`() = runTest {
        val outbox = RecordingOutboxDatabase(emptyList())
        var sent = false
        val summary = ProgressSyncEngine(outbox, RecordingPositionDatabase(), RecordingWrites()).push(
            listOf(outboxEntry("gone")), RecordingTransport(onPush = { sent = true }),
            ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertEquals(ProgressPushSummary(), summary)
        assertTrue(outbox.dispatchedIds.isEmpty())
        assertTrue(!sent)
    }

    @Test
    fun `clean remote replacement advances generation rather than resetting it`() = runTest {
        val positions = RecordingPositionDatabase()
        positions.upsertPosition(storedLinkedCopy(generation = 8L))
        ProgressSyncEngine(RecordingOutboxDatabase(emptyList()), positions, RecordingWrites())
            .applyRemote(remoteSnapshot(), "account-1")
        assertEquals(9L, positions.localPositions.last().localGeneration)
    }

    @Test
    fun `a clean identical pull also removes a leftover conflict baseline`() = runTest {
        val positions = RecordingPositionDatabase()
        val engine = ProgressSyncEngine(RecordingOutboxDatabase(emptyList()), positions, RecordingWrites())
        engine.applyRemote(remoteSnapshot(), "account-1")
        val savedCount = positions.localPositions.size
        positions.deletedRemoteBookIds.clear()
        engine.applyRemote(remoteSnapshot(), "account-1")
        assertEquals(savedCount, positions.localPositions.size)
        assertEquals(listOf("book-1"), positions.deletedRemoteBookIds)
    }

    @Test
    fun dirtyPullStoresRemoteBaselineWithoutReplacingLocalPosition() = runTest {
        val pending = outboxEntry(
            mutationId = "pending-1",
            entityId = "book-1",
        )
        val outbox = RecordingOutboxDatabase(listOf(pending))
        val positions = RecordingPositionDatabase()
        val engine = ProgressSyncEngine(outbox, positions, RecordingWrites())

        val outcome = engine.applyRemote(
            remote = remoteSnapshot(
                sourceDevice = ProgressSourceDevice("device-a", "Device A"),
            ),
            accountId = "account-1",
        )

        assertEquals(ProgressPullOutcome.PreservedLocalProgress, outcome)
        assertEquals(1, positions.remotePositions.size)
        assertEquals("device-a", positions.remotePositions.single().sourceDeviceId)
        assertEquals("Device A", positions.remotePositions.single().deviceName)
        assertTrue(positions.localPositions.isEmpty())
        assertTrue(positions.deletedRemoteBookIds.isEmpty())
    }

    @Test
    fun `a local choice queued after account binding survives an in flight cloud pull`() = runTest {
        val write = outboxEntry("new-choice").copy(cloudUserId = null)
        val outbox = RecordingOutboxDatabase(listOf(write))
        val positions = RecordingPositionDatabase()
        positions.upsertPosition(storedLinkedCopy(generation = 2L, origin = PositionEntity.ORIGIN_MANUAL))
        val outcome = ProgressSyncEngine(outbox, positions, RecordingWrites()).applyRemote(remoteSnapshot(), "account-1")
        assertEquals(ProgressPullOutcome.PreservedLocalProgress, outcome)
        assertEquals(PositionEntity.ORIGIN_MANUAL, positions.localPositions.last().origin)
        assertEquals(1, positions.remotePositions.size)
    }

    @Test
    fun cleanPullAppliesRemotePositionAndRemovesStaleBaseline() = runTest {
        val outbox = RecordingOutboxDatabase(emptyList())
        val positions = RecordingPositionDatabase()
        val engine = ProgressSyncEngine(outbox, positions, RecordingWrites())

        val outcome = engine.applyRemote(
            remote = remoteSnapshot(
                sourceDevice = ProgressSourceDevice("device-a", "Device A"),
            ),
            accountId = "account-1",
        )

        assertEquals(ProgressPullOutcome.AppliedToLocal, outcome)
        assertEquals("book-1", positions.localPositions.single().bookUuid)
        assertEquals("device-a", positions.localPositions.single().sourceDeviceId)
        assertEquals("Device A", positions.localPositions.single().deviceName)
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
        val engine = ProgressSyncEngine(outbox, positions, RecordingWrites())

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

    @Test
    fun refreshRemoteBookIdsAppliesCleanRemoteProgressWithoutAnOutboxMutation() = runTest {
        val outbox = RecordingOutboxDatabase(emptyList())
        val positions = RecordingPositionDatabase()
        val transport = RecordingTransport(
            fetchResults = mapOf("remote-book-1" to remoteSnapshot()),
        )
        val engine = ProgressSyncEngine(outbox, positions, RecordingWrites())

        val refreshed = engine.refreshRemoteBookIds(
            remoteBookIds = setOf("remote-book-1"),
            accountId = "account-1",
            transport = transport,
        )

        assertEquals(1, refreshed)
        assertEquals(setOf("remote-book-1"), transport.fetchedBookIds)
        assertEquals("book-1", positions.localPositions.single().bookUuid)
        assertEquals(listOf("book-1"), positions.deletedRemoteBookIds)
    }

    @Test
    fun `a conflict marks the entry, stores the remote position and schedules no retry`() =
        runTest {
            // Given
            val entry = outboxEntry(mutationId = "storyteller-409")
            val outbox = RecordingOutboxDatabase(listOf(entry))
            val positions = RecordingPositionDatabase()
            val engine = ProgressSyncEngine(outbox, positions, RecordingWrites())
            val transport = RecordingTransport(
                pushResults = listOf(
                    ProgressPushResult.Conflict("storyteller-409", remoteSnapshot()),
                ),
            )

            // When
            val summary = engine.push(
                entries = listOf(entry),
                transport = transport,
                codec = ProgressOutboxCodec { pending -> pending.toProgressMutation() },
            )

            // Then
            assertEquals(1, summary.conflictCount)
            assertEquals(0, summary.retryCount)
            assertEquals(listOf("storyteller-409"), outbox.conflictIds)
            assertTrue(outbox.failureIds.isEmpty())
            assertEquals("book-1", positions.remotePositions.single().bookUuid)
        }

    @Test
    fun `an automatic write that lost a conflict gives way to the server's reading`() =
        runTest {
            // Given: our linked_copy write is pending; the server holds newer reading.
            val entry = outboxEntry(mutationId = "linked-409")
            val outbox = RecordingOutboxDatabase(listOf(entry))
            val positions = RecordingPositionDatabase()
            positions.upsertPosition(storedLinkedCopy())
            val engine = ProgressSyncEngine(outbox, positions, RecordingWrites())

            // When
            engine.push(
                entries = listOf(entry),
                transport = RecordingTransport(
                    pushResults = listOf(
                        ProgressPushResult.Conflict("linked-409", remoteSnapshot()),
                    ),
                ),
                codec = ProgressOutboxCodec { pending -> pending.toProgressMutation() },
            )

            // Then
            val stored = positions.localPositions.last()
            assertEquals(PositionEntity.ORIGIN_REMOTE, stored.origin)
            assertTrue(positions.remotePositions.isEmpty())
            assertEquals(listOf("linked-409"), outbox.deletedIds)
            assertTrue(outbox.conflictIds.isEmpty())
        }

    @Test
    fun `old automatic conflict cannot overwrite newer automatic progress`() = runTest {
        val entry = outboxEntry("old", localGeneration = 1L)
        val outbox = RecordingOutboxDatabase(listOf(entry, outboxEntry("new", localGeneration = 2L)))
        val positions = RecordingPositionDatabase()
        positions.upsertPosition(storedLinkedCopy(generation = 2L))
        ProgressSyncEngine(outbox, positions, RecordingWrites()).push(
            listOf(entry), RecordingTransport(listOf(ProgressPushResult.Conflict("old", remoteSnapshot()))),
            ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertEquals(2L, positions.localPositions.last().localGeneration)
        assertEquals(PositionEntity.ORIGIN_LINKED_COPY, positions.localPositions.last().origin)
        assertEquals(1, positions.remotePositions.size)
        assertEquals(listOf("old"), outbox.conflictIds)
        assertTrue(outbox.deletedIds.isEmpty())
    }

    @Test
    fun `old automatic conflict cannot overwrite newer real reading`() = runTest {
        val entry = outboxEntry("old")
        val outbox = RecordingOutboxDatabase(listOf(entry))
        val positions = RecordingPositionDatabase()
        positions.upsertPosition(storedLinkedCopy(generation = 2L, origin = PositionEntity.ORIGIN_USER))
        ProgressSyncEngine(outbox, positions, RecordingWrites()).push(
            listOf(entry), RecordingTransport(listOf(ProgressPushResult.Conflict("old", remoteSnapshot()))),
            ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertEquals(PositionEntity.ORIGIN_USER, positions.localPositions.last().origin)
        assertEquals(2L, positions.localPositions.last().localGeneration)
        assertEquals(listOf("old"), outbox.conflictIds)
    }

    @Test
    fun `generation is checked again inside automatic conflict transaction`() = runTest {
        val entry = outboxEntry("old")
        val outbox = RecordingOutboxDatabase(listOf(entry))
        val positions = RecordingPositionDatabase()
        positions.upsertPosition(storedLinkedCopy())
        positions.beforeResolution = { positions.upsertPosition(storedLinkedCopy(generation = 2L)) }
        ProgressSyncEngine(outbox, positions, RecordingWrites()).push(
            listOf(entry), RecordingTransport(listOf(ProgressPushResult.Conflict("old", remoteSnapshot()))),
            ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertEquals(2L, positions.localPositions.last().localGeneration)
        assertEquals(1, positions.remotePositions.size)
        assertEquals(listOf("old"), outbox.conflictIds)
        assertTrue(outbox.deletedIds.isEmpty())
    }

    @Test
    fun `newer queued write prevents automatic replacement even if local generation matches`() = runTest {
        val entry = outboxEntry("old")
        val outbox = RecordingOutboxDatabase(listOf(entry, outboxEntry("new", localGeneration = 2L)))
        val positions = RecordingPositionDatabase()
        positions.upsertPosition(storedLinkedCopy())
        ProgressSyncEngine(outbox, positions, RecordingWrites()).push(
            listOf(entry), RecordingTransport(listOf(ProgressPushResult.Conflict("old", remoteSnapshot()))),
            ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertEquals(PositionEntity.ORIGIN_LINKED_COPY, positions.localPositions.last().origin)
        assertEquals(listOf("old"), outbox.conflictIds)
    }

    @Test
    fun `late conflict response after a user choice cannot recreate a conflict`() = runTest {
        val entry = outboxEntry("superseded")
        val outbox = RecordingOutboxDatabase(listOf(entry))
        val positions = RecordingPositionDatabase()
        positions.upsertPosition(storedLinkedCopy())
        val summary = ProgressSyncEngine(outbox, positions, RecordingWrites()).push(
            listOf(entry), RecordingTransport(
                listOf(ProgressPushResult.Conflict(entry.mutationId, remoteSnapshot())),
                onPush = { outbox.delete(entry.mutationId) },
            ), ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertEquals(0, summary.conflictCount)
        assertTrue(positions.remotePositions.isEmpty())
        assertTrue(outbox.conflictIds.isEmpty())
        assertEquals(PositionEntity.ORIGIN_LINKED_COPY, positions.localPositions.last().origin)
    }

    @Test
    fun `late acceptance after a user choice cannot update its revision`() = runTest {
        val entry = outboxEntry("superseded")
        val outbox = RecordingOutboxDatabase(listOf(entry))
        val positions = RecordingPositionDatabase()
        ProgressSyncEngine(outbox, positions, RecordingWrites()).push(
            listOf(entry), RecordingTransport(
                listOf(ProgressPushResult.Accepted(entry.mutationId, "42")),
                onPush = { outbox.delete(entry.mutationId) },
            ), ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertTrue(positions.revisionUpdates.isEmpty())
    }

    @Test
    fun `late rejection after a user choice does not schedule its superseded write`() = runTest {
        val entry = outboxEntry("superseded")
        val outbox = RecordingOutboxDatabase(listOf(entry))
        ProgressSyncEngine(outbox, RecordingPositionDatabase(), RecordingWrites()).push(
            listOf(entry), RecordingTransport(
                listOf(ProgressPushResult.Rejected(entry.mutationId, "offline")),
                onPush = { outbox.delete(entry.mutationId) },
            ), ProgressOutboxCodec { it.toProgressMutation() },
        )
        assertTrue(outbox.failureIds.isEmpty())
    }

    private fun storedLinkedCopy(generation: Long = 1L, origin: String = PositionEntity.ORIGIN_LINKED_COPY): PositionEntity = object : PositionEntity {
        override val bookUuid = "book-1"
        override val timestamp: Long? = 5L
        override val createdAt: String? = null
        override val updatedAt: String? = null
        override val locatorHref: String? = "c.xhtml"
        override val locatorType: String? = null
        override val locatorTitle: String? = null
        override val locatorTarget: Int? = null
        override val audioTimestampMs: Long? = null
        override val chapterIndex: Int? = null
        override val progression: Double? = 0.2
        override val totalChapters: Int? = null
        override val totalDurationMs: Long? = null
        override val totalProgression: Double? = 0.2
        override val position: Int? = null
        override val origin: String = origin
        override val localGeneration = generation
    }

    @Test
    fun `storyteller - a pull with our write's timestamp is an echo`() = runTest {
        // Given
        val (engine, positions) = engineWithWrite(marker = "1700", totalProgression = 0.4)

        // When
        engine.applyRemote(pull(marker = "1700", totalProgression = 0.4), "account-1")

        // Then
        assertEquals(PositionEntity.ORIGIN_LINKED_COPY, positions.localPositions.last().origin)
    }

    @Test
    fun `storyteller - another timestamp is real reading even at the same progression`() =
        runTest {
            // Given
            val (engine, positions) = engineWithWrite(marker = "1700", totalProgression = 0.4)

            // When
            engine.applyRemote(pull(marker = "1800", totalProgression = 0.4), "account-1")

            // Then
            assertEquals(PositionEntity.ORIGIN_REMOTE, positions.localPositions.last().origin)
        }

    @Test
    fun `parrot - the same revision is an echo, a newer one is real reading`() = runTest {
        // Given
        val (engine, positions) = engineWithWrite(marker = "12", totalProgression = 0.4)

        // When
        engine.applyRemote(pull(marker = "12", version = "12", totalProgression = 0.4), "a")
        val first = positions.localPositions.last().origin
        engine.applyRemote(pull(marker = "13", version = "13", totalProgression = 0.4), "a")

        // Then
        assertEquals(PositionEntity.ORIGIN_LINKED_COPY, first)
        assertEquals(PositionEntity.ORIGIN_REMOTE, positions.localPositions.last().origin)
    }

    @Test
    fun `audiobookshelf - within 1 percent is an echo, 5 percent further is real`() = runTest {
        // Given
        val (engine, positions) = engineWithWrite(marker = null, totalProgression = 0.40)

        // When
        engine.applyRemote(pull(marker = null, timestamp = 1, totalProgression = 0.405), "a")
        val first = positions.localPositions.last().origin
        engine.applyRemote(pull(marker = null, timestamp = 2, totalProgression = 0.45), "a")

        // Then
        assertEquals(PositionEntity.ORIGIN_LINKED_COPY, first)
        assertEquals(PositionEntity.ORIGIN_REMOTE, positions.localPositions.last().origin)
    }

    @Test
    fun `write log rows older than 7 days don't match`() = runTest {
        // Given
        val (engine, positions) = engineWithWrite(
            marker = "1700",
            totalProgression = 0.4,
            writtenAt = "2020-01-01T00:00:00Z",
        )

        // When
        engine.applyRemote(pull(marker = "1700", totalProgression = 0.4), "account-1")

        // Then
        assertEquals(PositionEntity.ORIGIN_REMOTE, positions.localPositions.last().origin)
    }

    @Test
    fun `re-pulling an unchanged snapshot leaves the stored row alone`() = runTest {
        // Given
        val positions = RecordingPositionDatabase()
        val engine = ProgressSyncEngine(
            RecordingOutboxDatabase(emptyList()),
            positions,
            RecordingWrites(),
        )
        val snapshot = pull(marker = "1700", timestamp = 1700, totalProgression = 0.4)
        engine.applyRemote(snapshot.copy(observedAt = "2026-10-01T10:00:00Z"), "account-1")
        val stored = positions.localPositions.single()

        // When
        engine.applyRemote(snapshot.copy(observedAt = "2026-10-01T11:00:00Z"), "account-1")

        // Then
        assertEquals(listOf(stored), positions.localPositions)
        assertEquals("2026-10-01T10:00:00Z", positions.localPositions.single().observedAt)
    }

    @Test
    fun `an acknowledged parrot write records its revision as the marker`() = runTest {
        // Given
        val writes = RecordingWrites(listOf(write(marker = null, totalProgression = null)))
        val entry = outboxEntry(mutationId = "m-1")
        val engine = ProgressSyncEngine(
            RecordingOutboxDatabase(listOf(entry)),
            RecordingPositionDatabase(),
            writes,
        )

        // When
        engine.push(
            entries = listOf(entry),
            transport = RecordingTransport(
                pushResults = listOf(ProgressPushResult.Accepted("m-1", "21")),
            ),
            codec = ProgressOutboxCodec { pending -> pending.toProgressMutation() },
        )

        // Then
        assertEquals("21", writes.writes.getValue("library:book-1").marker)
    }

    private suspend fun engineWithWrite(
        marker: String?,
        totalProgression: Double,
        writtenAt: String = kotlin.time.Clock.System.now().toString(),
    ): Pair<ProgressSyncEngine, RecordingPositionDatabase> {
        val positions = RecordingPositionDatabase()
        val writes = RecordingWrites(listOf(write(marker, totalProgression, writtenAt)))
        return ProgressSyncEngine(RecordingOutboxDatabase(emptyList()), positions, writes) to
            positions
    }

    private fun write(
        marker: String?,
        totalProgression: Double?,
        writtenAt: String = kotlin.time.Clock.System.now().toString(),
    ) = LinkedCopyWriteEntity(
        targetKey = "library:book-1",
        targetBookUuid = "book-1",
        sourceKey = "storyteller:st",
        sourceObservedAt = "2026-10-01T09:00:00Z",
        writtenAt = writtenAt,
        marker = marker,
        locatorHref = null,
        progression = null,
        totalProgression = totalProgression,
        audioMs = null,
    )

    private fun pull(
        marker: String?,
        totalProgression: Double,
        version: String? = null,
        timestamp: Long? = marker?.toLongOrNull(),
    ) = remoteSnapshot().copy(
        version = version,
        marker = marker,
        snapshot = emptySnapshot().copy(timestamp = timestamp, totalProgression = totalProgression),
    )

    @Test
    fun `a pulled position is stored as remote reading with its observation time`() = runTest {
        // Given
        val positions = RecordingPositionDatabase()
        val engine = ProgressSyncEngine(
            RecordingOutboxDatabase(emptyList()),
            positions,
            RecordingWrites(),
        )

        // When
        engine.applyRemote(
            remote = remoteSnapshot().copy(observedAt = "1000"),
            accountId = "account-1",
        )

        // Then
        val stored = positions.localPositions.single()
        assertEquals(PositionEntity.ORIGIN_REMOTE, stored.origin)
        assertEquals("1970-01-01T00:00:01Z", stored.observedAt)
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

    private fun remoteSnapshot(
        sourceDevice: ProgressSourceDevice? = null,
    ) = RemoteProgressSnapshot(
        entityId = "book-1",
        remoteBookId = "remote-book-1",
        libraryBookId = "book-1",
        kind = ProgressKind.EBOOK,
        snapshot = emptySnapshot(),
        version = "9",
        observedAt = "2026-09-22T10:01:00Z",
        sourceDevice = sourceDevice,
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
    private val onPush: suspend () -> Unit = {},
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
    ): List<ProgressPushResult> {
        onPush()
        return pushResults
    }
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

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> =
        entries.filter { it.cloudUserId == cloudUserId }

    override suspend fun getPendingIncludingUnassigned(cloudUserId: String): List<SyncOutboxEntry> =
        entries.filter { it.cloudUserId == cloudUserId || it.cloudUserId == null }

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) {
        dispatchedIds += mutationId
    }

    val conflictIds = mutableListOf<String>()

    override suspend fun markConflict(mutationId: String, error: String) {
        conflictIds += mutationId
    }

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
    var beforeResolution: suspend () -> Unit = {}

    override suspend fun resolvePositionConflict(
        position: PositionEntity,
        mutation: SyncOutboxEntry?,
        expectedLocalGeneration: Long,
        destinationId: String?,
    ): Boolean {
        beforeResolution()
        if (getPositionByBookUuid(position.bookUuid)?.localGeneration != expectedLocalGeneration) return false
        upsertPosition(position)
        deleteRemotePosition(position.bookUuid)
        return true
    }

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

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? =
        localPositions.lastOrNull { position -> position.bookUuid == bookUuid }

    override suspend fun getAllPositions(): List<PositionEntity> = localPositions.toList()

    override suspend fun deletePosition(bookUuid: String) = Unit

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = emptyFlow()

    override fun observeAllPositions(): Flow<List<PositionEntity>> = emptyFlow()

    override suspend fun clearAllData() = Unit
}

internal class RecordingWrites(
    initial: List<LinkedCopyWriteEntity> = emptyList(),
) : LinkedCopyWritesDatabase {
    val writes = initial.associateBy { write -> write.targetKey }.toMutableMap()

    override suspend fun replace(write: LinkedCopyWriteEntity, deleteWrittenBefore: String) {
        writes.values.removeAll { existing -> existing.writtenAt < deleteWrittenBefore }
        writes[write.targetKey] = write
    }

    override suspend fun getWrite(targetKey: String, notBefore: String): LinkedCopyWriteEntity? =
        writes[targetKey]?.takeIf { write -> write.writtenAt >= notBefore }

    override suspend fun getWriteForBook(
        bookUuid: String,
        notBefore: String,
    ): LinkedCopyWriteEntity? = writes.values
        .filter { write -> write.targetBookUuid == bookUuid && write.writtenAt >= notBefore }
        .maxByOrNull { write -> write.writtenAt }

    override suspend fun setMarker(targetKey: String, marker: String) {
        writes[targetKey]?.let { write -> writes[targetKey] = write.copy(marker = marker) }
    }
}
