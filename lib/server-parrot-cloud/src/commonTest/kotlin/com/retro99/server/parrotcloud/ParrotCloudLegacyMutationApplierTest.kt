package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ParrotCloudLegacyMutationApplierTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun acceptedMutationDecodesPayloadAndAppliesRemoteIdentity() = runTest {
        val database = RecordingLibraryBooksDatabase()
        val applier = ParrotCloudLegacyMutationApplier(LibraryBookSyncApplier(database))

        applier.onAccepted(
            entry = entry(json.encodeToString(bookPayload())),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                cloudBookId = "cloud-book-accepted",
                revision = 12L,
                payload = null,
                reason = null,
            ),
        )

        assertEquals("cloud-book-accepted", database.upserted.single().cloudBookId)
        assertEquals("book title", database.upserted.single().title)
        assertEquals(12L, database.upserted.single().remoteRevision)
    }

    @Test
    fun conflictPayloadIsAppliedAsRemoteLibraryState() = runTest {
        val database = RecordingLibraryBooksDatabase()
        val applier = ParrotCloudLegacyMutationApplier(LibraryBookSyncApplier(database))

        applier.onConflict(
            entry = entry("{}"),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "conflict",
                cloudBookId = null,
                revision = 13L,
                payload = json.encodeToString(bookPayload().copy(title = "remote title")),
                reason = "stale revision",
            ),
        )

        assertEquals("remote title", database.upserted.single().title)
        assertEquals(13L, database.upserted.single().remoteRevision)
    }

    private fun entry(payload: String) = SyncOutboxEntry(
        mutationId = "mutation-1",
        cloudUserId = "account",
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
        entityId = "library-book-1",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = payload,
        baseRevision = 4L,
        createdAt = "2026-09-22T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    private fun bookPayload() = ParrotCloudBookPayload(
        libraryBookId = "library-book-1",
        cloudBookId = "cloud-book-1",
        contentHash = "hash",
        contentHashAlgorithm = "sha256",
        title = "book title",
        author = "author",
        format = "ebook",
        metadataJson = "metadata",
        remoteRevision = 9L,
    )
}

private class RecordingLibraryBooksDatabase : LibraryBooksDatabase {
    val upserted = mutableListOf<LibraryBookEntity>()

    override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        upserted += book
    }

    override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) = Unit

    override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = emptyFlow()

    override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = null

    override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? = null

    override suspend fun getLibraryBookByContentHash(
        contentHashAlgorithm: String,
        contentHash: String,
    ): LibraryBookEntity? = null

    override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? = null

    override suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) = Unit

    override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) = Unit

    override suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> = emptyList()

    override suspend fun getLocalBookFileByImportedBookUuid(importedBookUuid: String): LocalBookFileEntity? = null

    override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) = Unit
}
