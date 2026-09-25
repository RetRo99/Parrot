package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.LibraryMutationSyncTransport
import com.retro99.sync.domain.SyncChangePage
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryMutationSyncEngineTest {

    @Test
    fun acceptedRetryableRejectedAndOmittedResultsKeepDistinctDurableStates() = runTest {
        val accepted = entry("accepted")
        val retryable = entry("retryable")
        val rejected = entry("rejected")
        val omitted = entry("omitted")
        val outbox = RecordingLibraryMutationOutbox()
        val transport = RecordingLibraryMutationTransport(
            responses = listOf(
                SyncMutationResponse(
                    mutationId = accepted.mutationId,
                    status = "accepted",
                    cloudBookId = "cloud-book",
                    revision = 12L,
                    payload = null,
                    reason = null,
                ),
                SyncMutationResponse(
                    mutationId = retryable.mutationId,
                    status = "retryable",
                    cloudBookId = null,
                    revision = null,
                    payload = null,
                    reason = "book_files_must_be_removed_first",
                    retryAfterMillis = 30_000L,
                ),
                SyncMutationResponse(
                    mutationId = rejected.mutationId,
                    status = "rejected",
                    cloudBookId = null,
                    revision = null,
                    payload = null,
                    reason = "invalid_group_decision",
                ),
            ),
        )
        val applier = RecordingLibraryMutationApplier()
        val engine = LibraryMutationSyncEngine(outbox)

        val summary = engine.push(
            entries = listOf(accepted, retryable, rejected, omitted),
            transport = transport,
            cursor = "opaque-cursor",
            applier = applier,
        )

        assertEquals(1, summary.acknowledgedCount)
        assertEquals(1, summary.rejectedCount)
        assertEquals(1, summary.retryCount)
        assertEquals(1, summary.unresolvedCount)
        assertEquals(
            listOf(
                accepted.mutationId,
                retryable.mutationId,
                rejected.mutationId,
                omitted.mutationId,
            ),
            outbox.dispatchedIds,
        )
        assertEquals(listOf(accepted.mutationId), outbox.deletedIds)
        assertEquals(listOf(retryable.mutationId), outbox.failureIds)
        assertEquals(listOf(rejected.mutationId), outbox.conflictIds)
        assertEquals("invalid_group_decision", outbox.conflictReasons[rejected.mutationId])
        assertEquals(listOf(accepted.mutationId), applier.acceptedIds)
        assertEquals(
            listOf(
                accepted.mutationId,
                retryable.mutationId,
                rejected.mutationId,
                omitted.mutationId,
            ),
            transport.receivedMutationIds,
        )
        assertEquals("opaque-cursor", transport.receivedCursor)
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

private class RecordingLibraryMutationApplier : LibraryMutationApplier {
    val acceptedIds = mutableListOf<String>()

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
}

private class RecordingLibraryMutationOutbox : SyncOutboxDatabase {
    val dispatchedIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    val failureIds = mutableListOf<String>()
    val conflictIds = mutableListOf<String>()
    val conflictReasons = mutableMapOf<String, String>()

    override suspend fun enqueue(entry: SyncOutboxEntry) = Unit

    override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = emptyList()

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) {
        dispatchedIds += mutationId
    }

    override suspend fun markConflict(mutationId: String, error: String) {
        conflictIds += mutationId
        conflictReasons[mutationId] = error
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

    override suspend fun coalesce(
        entityType: String,
        entityId: String,
        entry: SyncOutboxEntry,
    ) = Unit

    override suspend fun clearAllData() = Unit
}
