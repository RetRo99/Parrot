package com.retro99.sync.data

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryBookSyncApplierTest {

    @Test
    fun remoteSnapshotKeepsExistingCanonicalLibraryIdentity() = runTest {
        val database = RecordingLibraryBooksDatabase(
            existingByCloudBookId = ExistingLibraryBook(
                libraryBookId = "canonical-library-id",
                contentHash = "hash",
                title = "old title",
            ),
        )
        val applier = LibraryBookSyncApplier(database)

        applier.applyRemote(snapshot())

        assertEquals("canonical-library-id", database.upserted.single().libraryBookId)
        assertEquals("new title", database.upserted.single().title)
        assertEquals(9L, database.upserted.single().remoteRevision)
    }

    @Test
    fun acceptedMutationPreservesExistingLocalMetadataWhileAttachingRemoteIdentity() = runTest {
        val database = RecordingLibraryBooksDatabase(
            existingById = ExistingLibraryBook(
                libraryBookId = "library-id",
                contentHash = "local-hash",
                title = "local title",
                metadataJson = "local-metadata",
            ),
        )
        val applier = LibraryBookSyncApplier(database)

        applier.applyAccepted(
            entry = entry(),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                cloudBookId = "cloud-book-id",
                revision = 12L,
                payload = null,
                reason = null,
            ),
            snapshot = snapshot().copy(title = "remote title"),
        )

        assertEquals("cloud-book-id", database.upserted.single().cloudBookId)
        assertEquals("local title", database.upserted.single().title)
        assertEquals("local-metadata", database.upserted.single().metadataJson)
        assertEquals(12L, database.upserted.single().remoteRevision)
    }

    private fun snapshot() = SyncLibraryBookSnapshot(
        libraryBookId = "incoming-library-id",
        cloudBookId = "cloud-book-id",
        contentHash = "hash",
        contentHashAlgorithm = "sha256",
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
        entityId = "library-id",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        baseRevision = null,
        createdAt = "2026-09-22T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )
}

private data class ExistingLibraryBook(
    override val libraryBookId: String,
    override val contentHash: String?,
    override val title: String,
    override val metadataJson: String? = null,
    override val cloudBookId: String? = null,
    override val contentHashAlgorithm: String? = "sha256",
    override val author: String? = "existing author",
    override val format: String = "ebook",
    override val remoteRevision: Long? = 4L,
    override val deletedAt: String? = null,
) : LibraryBookEntity

private class RecordingLibraryBooksDatabase(
    private val existingById: LibraryBookEntity? = null,
    private val existingByCloudBookId: LibraryBookEntity? = null,
) : LibraryBooksDatabase {
    val upserted = mutableListOf<LibraryBookEntity>()

    override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        upserted += book
    }

    override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) = Unit

    override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = emptyFlow()

    override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = existingById

    override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? = null

    override suspend fun getLibraryBookByContentHash(
        contentHashAlgorithm: String,
        contentHash: String,
    ): LibraryBookEntity? = null

    override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? = existingByCloudBookId

    override suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) = Unit

    override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) = Unit

    override suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> = emptyList()

    override suspend fun getLocalBookFileByImportedBookUuid(importedBookUuid: String): LocalBookFileEntity? = null

    override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) = Unit
}
