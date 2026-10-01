package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ParrotCloudLibraryMutationApplierTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun acceptedMutationRecordsTheServerRevision() = runTest {
        val database = ParrotTestLibraryBooksDatabase(parrotTestBook(BOOK_ID))
        val applier = ParrotCloudLibraryMutationApplier(
            LibraryBookSyncApplier(database),
            ParrotCloudBookLinkSync(ParrotTestBookLinksDatabase()),
        )

        applier.onAccepted(
            entry = entry(json.encodeToString(bookPayload())),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                revision = 12L,
                payload = null,
                reason = null,
            ),
        )

        assertEquals(BOOK_ID, database.upserted.single().libraryBookId)
        assertEquals(12L, database.upserted.single().remoteRevision)
    }

    @Test
    fun conflictPayloadIsAppliedAsRemoteLibraryState() = runTest {
        val database = ParrotTestLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            LibraryBookSyncApplier(database),
            ParrotCloudBookLinkSync(ParrotTestBookLinksDatabase()),
        )

        applier.onConflict(
            entry = entry("{}"),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "conflict",
                revision = 13L,
                payload = json.encodeToString(bookPayload().copy(title = "remote title")),
                reason = "stale revision",
            ),
        )

        assertEquals("remote title", database.upserted.single().title)
        assertEquals(13L, database.upserted.single().remoteRevision)
    }

    @Test
    fun acceptedSessionMutationNeedsNoLocalBookMetadataUpdate() = runTest {
        val database = ParrotTestLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            LibraryBookSyncApplier(database),
            ParrotCloudBookLinkSync(ParrotTestBookLinksDatabase()),
        )

        applier.onAccepted(
            entry = entry(payload = "{}").copy(
                entityType = SyncOutboxEntry.ENTITY_TYPE_READING_SESSION,
            ),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                revision = 1L,
                payload = null,
                reason = null,
            ),
        )

        assertEquals(emptyList(), database.upserted)
    }

    @Test
    fun conflictSessionMutationDoesNotTouchLibraryState() = runTest {
        val database = ParrotTestLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            LibraryBookSyncApplier(database),
            ParrotCloudBookLinkSync(ParrotTestBookLinksDatabase()),
        )

        applier.onConflict(
            entry = entry("{}").copy(
                entityType = SyncOutboxEntry.ENTITY_TYPE_READING_SESSION,
            ),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "conflict",
                revision = 1L,
                payload = """{"title":"remote title"}""",
                reason = "stale revision",
            ),
        )

        assertEquals(emptyList(), database.upserted)
    }

    private fun entry(payload: String) = SyncOutboxEntry(
        mutationId = "mutation-1",
        cloudUserId = "account",
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
        entityId = BOOK_ID,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = payload,
        baseRevision = 4L,
        createdAt = "2026-09-22T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    @Test
    fun `a duplicate saves the server's book by its own id`() = runTest {
        // Given
        val database = ParrotTestLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            LibraryBookSyncApplier(database),
            ParrotCloudBookLinkSync(ParrotTestBookLinksDatabase()),
        )
        val serverBook = bookPayload().copy(libraryBookId = EXISTING_ID, remoteRevision = 7L)

        // When
        applier.onDuplicate(
            entry = entry(json.encodeToString(bookPayload())),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "duplicate",
                revision = null,
                payload = json.encodeToString(serverBook),
                reason = null,
                existingBookId = EXISTING_ID,
            ),
        )

        // Then
        val saved = database.upserted.single()
        assertEquals(EXISTING_ID, saved.libraryBookId)
        assertEquals(7L, saved.remoteRevision)
        assertEquals("hash", saved.sourceContentHash)
    }

    private fun bookPayload() = ParrotCloudBookPayload(
        libraryBookId = BOOK_ID,
        sourceContentHash = "hash",
        sourceContentHashAlgorithm = "sha-256-v1",
        title = "book title",
        author = "author",
        format = "ebook",
        metadataJson = "metadata",
        remoteRevision = 9L,
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-4111-8111-111111111111"
        const val EXISTING_ID = "22222222-2222-4222-8222-222222222222"
    }
}
