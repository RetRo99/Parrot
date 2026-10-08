package com.retro99.sync.data

import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryBookSyncApplierTest {

    @Test
    fun `a pulled book creates a library row by its id`() = runTest {
        // Given
        val database = RecordingLibraryBooksDatabase()
        val applier = LibraryBookSyncApplier(database)

        // When
        applier.applyRemote(snapshot())

        // Then
        val book = database.upserted.single()
        assertEquals(BOOK_ID, book.libraryBookId)
        assertEquals("new title", book.title)
        assertEquals("hash", book.sourceContentHash)
        assertEquals(9L, book.remoteRevision)
        assertNull(book.coverPath)
        assertTrue(book.addedAt.isNotBlank())
    }

    @Test
    fun `a pulled book keeps what only this device knows`() = runTest {
        // Given
        val existing = LibraryBookEntity(
            libraryBookId = BOOK_ID,
            title = "old title",
            description = "local description",
            coverPath = "/covers/book.png",
            addedAt = "2026-01-01T00:00:00Z",
            lastOpenedAt = "2026-02-01T00:00:00Z",
            sourceContentHash = "local-hash",
            sourceContentHashAlgorithm = "sha-256-v1",
        )
        val database = RecordingLibraryBooksDatabase(existing)
        val applier = LibraryBookSyncApplier(database)

        // When
        applier.applyRemote(snapshot().copy(sourceContentHash = null))

        // Then
        val book = database.upserted.single()
        assertEquals("new title", book.title)
        assertEquals("local description", book.description)
        assertEquals("/covers/book.png", book.coverPath)
        assertEquals("2026-01-01T00:00:00Z", book.addedAt)
        assertEquals("2026-02-01T00:00:00Z", book.lastOpenedAt)
        assertEquals("local-hash", book.sourceContentHash)
        assertEquals(9L, book.remoteRevision)
    }

    @Test
    fun `an accepted upsert records the server revision`() = runTest {
        // Given
        val existing = LibraryBookEntity(
            libraryBookId = BOOK_ID,
            title = "local title",
            addedAt = "2026-01-01T00:00:00Z",
            metadataJson = "local-metadata",
        )
        val database = RecordingLibraryBooksDatabase(existing)
        val applier = LibraryBookSyncApplier(database)

        // When
        applier.applyAccepted(
            entry = entry(),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                revision = 12L,
                payload = null,
                reason = null,
            ),
            snapshot = snapshot().copy(title = "remote title"),
        )

        // Then
        val book = database.upserted.single()
        assertEquals("local title", book.title)
        assertEquals("local-metadata", book.metadataJson)
        assertEquals(12L, book.remoteRevision)
    }

    @Test
    fun `an accepted upsert for a book deleted meanwhile does not bring it back`() = runTest {
        // Given
        val database = RecordingLibraryBooksDatabase()
        val applier = LibraryBookSyncApplier(database)

        // When
        applier.applyAccepted(
            entry = entry(),
            response = SyncMutationResponse("mutation-1", "accepted", 12L, null, null),
            snapshot = snapshot(),
        )

        // Then
        assertTrue(database.upserted.isEmpty())
    }

    private fun snapshot() = SyncLibraryBookSnapshot(
        libraryBookId = BOOK_ID,
        sourceContentHash = "hash",
        sourceContentHashAlgorithm = "sha-256-v1",
        title = "new title",
        author = "author",
        format = "ebook",
        remoteRevision = 9L,
        metadataJson = "remote-metadata",
    )

    private fun entry() = SyncOutboxEntry(
        mutationId = "mutation-1",
        cloudUserId = "account",
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
        entityId = BOOK_ID,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        baseRevision = null,
        createdAt = "2026-09-22T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-4111-8111-111111111111"
    }
}

private class RecordingLibraryBooksDatabase(
    private val existing: LibraryBookEntity? = null,
) : LibraryBooksDatabase {
    val upserted = mutableListOf<LibraryBookEntity>()

    override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        upserted += book
    }

    override fun observeLibraryBooks(): Flow<List<LibraryBookEntity>> = emptyFlow()

    override suspend fun getLibraryBookById(libraryBookId: String) =
        existing?.takeIf { book -> book.libraryBookId == libraryBookId }

    override suspend fun findLibraryBookBySourceHash(algorithm: String, hash: String) =
        error("Unused")

    override suspend fun countLibraryBooksWithDeviceFiles(): Int = error("Unused")

    override suspend fun updateLastOpenedAt(libraryBookId: String, lastOpenedAt: String) =
        error("Unused")

    override suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    ) = error("Unused")

    override suspend fun insertBookWithoutSync(book: LibraryBookEntity, file: DeviceFileEntity) =
        error("Unused")

    override suspend fun deleteBookFromDevice(libraryBookId: String) = error("Unused")
}
