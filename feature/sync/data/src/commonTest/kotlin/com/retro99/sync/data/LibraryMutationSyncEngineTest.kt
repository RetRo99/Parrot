package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.LibraryBookMerger
import com.retro99.sync.domain.LibraryMutationSyncTransport
import com.retro99.sync.domain.SyncChangePage
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryMutationSyncEngineTest {

    @Test
    fun acceptedRejectedAndOmittedResultsKeepDistinctDurableStates() = runTest {
        val accepted = entry("accepted")
        val rejected = entry("rejected")
        val omitted = entry("omitted")
        val outbox = RecordingLibraryMutationOutbox()
        val transport = RecordingLibraryMutationTransport(
            responses = listOf(
                SyncMutationResponse(
                    mutationId = accepted.mutationId,
                    status = "accepted",
                    revision = 12L,
                    payload = null,
                    reason = null,
                ),
                SyncMutationResponse(
                    mutationId = rejected.mutationId,
                    status = "rate_limited",
                    revision = null,
                    payload = null,
                    reason = "try later",
                    retryAfterMillis = 5_000L,
                ),
            ),
        )
        val applier = RecordingLibraryMutationApplier()
        val engine = LibraryMutationSyncEngine(outbox, RecordingMerger())

        val summary = engine.push(
            entries = listOf(accepted, rejected, omitted),
            transport = transport,
            cursor = "opaque-cursor",
            applier = applier,
        )

        assertEquals(1, summary.acknowledgedCount)
        assertEquals(1, summary.retryCount)
        assertEquals(1, summary.unresolvedCount)
        assertEquals(
            listOf(accepted.mutationId, rejected.mutationId, omitted.mutationId),
            outbox.dispatchedIds,
        )
        assertEquals(listOf(accepted.mutationId), outbox.deletedIds)
        assertEquals(listOf(rejected.mutationId), outbox.failureIds)
        assertEquals(listOf(accepted.mutationId), applier.acceptedIds)
        assertEquals(
            listOf(accepted.mutationId, rejected.mutationId, omitted.mutationId),
            transport.receivedMutationIds,
        )
        assertEquals("opaque-cursor", transport.receivedCursor)
    }

    @Test
    fun `a duplicate saves the server book, merges into it and resolves the entry`() = runTest {
        // Given
        val duplicate = entry("duplicate")
        val outbox = RecordingLibraryMutationOutbox()
        val merger = RecordingMerger()
        val transport = RecordingLibraryMutationTransport(
            responses = listOf(
                SyncMutationResponse(
                    mutationId = duplicate.mutationId,
                    status = "duplicate",
                    revision = null,
                    payload = """{"library_book_id":"existing-book"}""",
                    reason = null,
                    existingBookId = "existing-book",
                ),
            ),
        )
        val applier = RecordingLibraryMutationApplier()
        val engine = LibraryMutationSyncEngine(outbox, merger)

        // When
        val summary = engine.push(
            entries = listOf(duplicate),
            transport = transport,
            cursor = null,
            applier = applier,
        )

        // Then
        assertEquals(listOf(duplicate.mutationId), applier.duplicateIds)
        assertEquals(listOf(duplicate.entityId to "existing-book"), merger.merges)
        assertEquals(listOf(duplicate.mutationId), outbox.deletedIds)
        assertEquals(1, summary.acknowledgedCount)
    }

    @Test
    fun `a conflict the applier resolves leaves the outbox instead of being preserved`() =
        runTest {
            // Given
            val resolved = entry("resolved")
            val preserved = entry("preserved")
            val outbox = RecordingLibraryMutationOutbox()
            val transport = RecordingLibraryMutationTransport(
                responses = listOf(resolved, preserved).map { entry ->
                    SyncMutationResponse(
                        mutationId = entry.mutationId,
                        status = "conflict",
                        revision = 3L,
                        payload = "{}",
                        reason = null,
                    )
                },
            )
            val applier = RecordingLibraryMutationApplier(
                discardedConflictIds = setOf(resolved.mutationId),
            )
            val engine = LibraryMutationSyncEngine(outbox, RecordingMerger())

            // When
            val summary = engine.push(
                entries = listOf(resolved, preserved),
                transport = transport,
                cursor = null,
                applier = applier,
            )

            // Then
            assertEquals(listOf(resolved.mutationId), outbox.deletedIds)
            assertEquals(listOf(preserved.mutationId), outbox.conflictIds)
            assertEquals(2, summary.conflictCount)
        }

    private fun entry(id: String) = SyncOutboxEntry(
        mutationId = id,
        cloudUserId = "account",
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
        entityId = "library-book-$id",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        baseRevision = null,
        createdAt = "2026-09-22T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )
}

private class RecordingLibraryMutationTransport(
    private val responses: List<SyncMutationResponse>,
) : LibraryMutationSyncTransport {
    var receivedCursor: String? = null
    var receivedMutationIds: List<String> = emptyList()

    override suspend fun push(
        mutations: List<com.retro99.sync.domain.SyncMutationRequest>,
        cursor: String?,
    ): List<SyncMutationResponse> {
        receivedCursor = cursor
        receivedMutationIds = mutations.map { mutation -> mutation.mutationId }
        return responses
    }

    override suspend fun pull(cursor: String?, limit: Int): SyncChangePage {
        return SyncChangePage(emptyList(), cursor, false)
    }
}

private class RecordingLibraryMutationApplier(
    private val discardedConflictIds: Set<String> = emptySet(),
) : LibraryMutationApplier {
    val acceptedIds = mutableListOf<String>()

    override fun discardsConflict(entry: SyncOutboxEntry): Boolean =
        entry.mutationId in discardedConflictIds

    override suspend fun onAccepted(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        acceptedIds += entry.mutationId
    }

    override suspend fun onConflict(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) = Unit

    val duplicateIds = mutableListOf<String>()

    override suspend fun onDuplicate(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        duplicateIds += entry.mutationId
    }
}

private class RecordingMerger : LibraryBookMerger {
    val merges = mutableListOf<Pair<String, String>>()

    override suspend fun merge(fromId: String, intoId: String) {
        merges += fromId to intoId
    }
}

private class RecordingLibraryMutationOutbox : SyncOutboxDatabase {
    val dispatchedIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    val failureIds = mutableListOf<String>()
    val conflictIds = mutableListOf<String>()

    override suspend fun enqueue(entry: SyncOutboxEntry) = Unit

    override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = emptyList()

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) {
        dispatchedIds += mutationId
    }

    override suspend fun markConflict(mutationId: String, error: String) {
        conflictIds += mutationId
    }

    override suspend fun delete(mutationId: String) {
        deletedIds += mutationId
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
