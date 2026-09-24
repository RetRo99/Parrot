package com.retro99.books.data.transfer

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.EpubMetadata
import com.retro99.books.data.EpubMetadataExtractor
import com.retro99.books.data.sha256
import com.retro99.books.data.toHexString
import com.retro99.books.data.model.ImportedBookLocalModel
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.library.LibraryBookMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadFinalizerTest {
    @Test
    fun downloadedBookAndTransferArePersistedThroughSingleAtomicDatabaseCall() = runTest {
        val bytes = "complete restored book".encodeToByteArray()
        val hash = sha256(bytes).toHexString()
        val fixture = fixture(bytes, hash)
        val request = request(hash, bytes.size.toLong())
        val transfer = downloadTransfer(request.libraryBookId, bytes.size.toLong())

        val completed = fixture.finalizer.finalize(transfer, request)

        assertEquals(1, fixture.importedDatabase.restoredWrites.size)
        val write = fixture.importedDatabase.restoredWrites.single()
        assertEquals("local-restored", write.book.uuid)
        assertEquals("local-restored", write.localBookFile.importedBookUuid)
        assertEquals("completed", write.transfer.state)
        assertEquals(request.libraryBookId, write.libraryBook.libraryBookId)
        assertEquals("completed", completed.state)
        assertEquals("/imports/local-restored_ebook.epub", write.book.filePath)
        assertTrue("/imports/local-restored_ebook.epub" in fixture.fileStore.files)
        assertTrue("/staging/download-1.part" !in fixture.fileStore.files)
        assertEquals(0, fixture.importedDatabase.legacyWriteCount)
    }

    @Test
    fun restoreDoesNotUseMutationOutboxWritePath() = runTest {
        val bytes = "restore without upload mutation".encodeToByteArray()
        val hash = sha256(bytes).toHexString()
        val fixture = fixture(bytes, hash)

        val request = request(hash, bytes.size.toLong())
        fixture.finalizer.finalize(downloadTransfer(request.libraryBookId, bytes.size.toLong()), request)

        assertEquals(1, fixture.importedDatabase.restoredWrites.size)
        assertEquals(0, fixture.importedDatabase.legacyWriteCount)
    }

    @Test
    fun verifiedExistingLocalBookIsAttachedInsteadOfCopiedAgain() = runTest {
        val bytes = "already imported".encodeToByteArray()
        val hash = sha256(bytes).toHexString()
        val existing = importedBook(hash, bytes.size.toLong(), "/imports/existing.epub")
        val fixture = fixture(bytes, hash, existingBook = existing)
        fixture.fileStore.files[existing.filePath] = bytes

        val request = request(hash, bytes.size.toLong())
        fixture.finalizer.finalize(downloadTransfer(request.libraryBookId, bytes.size.toLong()), request)

        val restored = fixture.importedDatabase.restoredWrites.single()
        assertSame(existing, restored.book)
        assertEquals("existing-local", restored.localBookFile.importedBookUuid)
        assertTrue(fixture.fileStore.files.containsKey(existing.filePath))
        assertTrue("/staging/download-1.part" !in fixture.fileStore.files)
        assertEquals(0, fixture.metadataExtractor.calls)
    }

    @Test
    fun restoredPositionIsLookedUpAndRemappedByLibraryBookId() = runTest {
        val bytes = "book with reading position".encodeToByteArray()
        val hash = sha256(bytes).toHexString()
        val libraryBookId = request(hash, bytes.size.toLong()).libraryBookId
        val position = position(bookUuid = "remote-book-id", libraryBookId = libraryBookId)
        val fixture = fixture(bytes, hash, position = position)

        fixture.finalizer.finalize(
            downloadTransfer(libraryBookId, bytes.size.toLong()),
            request(hash, bytes.size.toLong()),
        )

        assertEquals(listOf(libraryBookId), fixture.positionDatabase.lookups)
        val restoredPosition = fixture.importedDatabase.restoredWrites.single().position
        assertEquals("local-restored", restoredPosition?.bookUuid)
        assertEquals(libraryBookId, restoredPosition?.libraryBookId)
        assertEquals(position.position, restoredPosition?.position)
        assertEquals(position.locatorHref, restoredPosition?.locatorHref)
    }

    @Test
    fun missingOrTruncatedStagingFileIsNotPublishedAsReadableBook() = runTest {
        val bytes = "full file".encodeToByteArray()
        val hash = sha256(bytes).toHexString()
        val fixture = fixture(bytes, hash, seedStaging = false)
        fixture.fileStore.files["/staging/download-1.part"] = "part".encodeToByteArray()

        val request = request(hash, bytes.size.toLong())
        assertFailsWith<IllegalStateException> {
            fixture.finalizer.finalize(downloadTransfer(request.libraryBookId, bytes.size.toLong()), request)
        }

        assertTrue(fixture.importedDatabase.restoredWrites.isEmpty())
        assertTrue("/imports/local-restored_ebook.epub" !in fixture.fileStore.files)
        assertEquals(0, fixture.metadataExtractor.calls)
    }

    private fun fixture(
        bytes: ByteArray,
        hash: String,
        existingBook: ImportedBookEntity? = null,
        position: PositionEntity? = null,
        seedStaging: Boolean = true,
    ): Fixture {
        val fileStore = FakeFileStore().also { store ->
            if (seedStaging) store.files["/staging/download-1.part"] = bytes
        }
        val importedDatabase = RecordingImportedBooksDatabase(existingBook)
        val positionDatabase = RecordingPositionDatabase(position)
        val libraryBook = libraryBook(hash)
        val libraryDatabase = object : EmptyLibraryBooksDatabase() {
            override suspend fun getLibraryBookByCloudBookId(cloudBookId: String) =
                libraryBook.takeIf { it.cloudBookId == cloudBookId }

            override suspend fun getLibraryBookByContentHash(
                contentHashAlgorithm: String,
                contentHash: String,
            ) = libraryBook.takeIf {
                it.contentHashAlgorithm == contentHashAlgorithm && it.contentHash == contentHash
            }
        }
        val metadataExtractor = RecordingMetadataExtractor()
        return Fixture(
            finalizer = DownloadFinalizer(
                importedBooksDatabase = importedDatabase,
                libraryBooksDatabase = libraryDatabase,
                booksDatabase = positionDatabase,
                metadataExtractor = metadataExtractor,
                fileStore = fileStore,
            ),
            importedDatabase = importedDatabase,
            positionDatabase = positionDatabase,
            fileStore = fileStore,
            metadataExtractor = metadataExtractor,
        )
    }

    private data class Fixture(
        val finalizer: DownloadFinalizer,
        val importedDatabase: RecordingImportedBooksDatabase,
        val positionDatabase: RecordingPositionDatabase,
        val fileStore: FakeFileStore,
        val metadataExtractor: RecordingMetadataExtractor,
    )

    private data class RestoredWrite(
        val book: ImportedBookEntity,
        val libraryBook: LibraryBookEntity,
        val localBookFile: LocalBookFileEntity,
        val transfer: CloudFileTransferEntity,
        val position: PositionEntity?,
    )

    private class RecordingImportedBooksDatabase(
        private val existingBook: ImportedBookEntity?,
    ) : EmptyImportedBooksDatabase() {
        val restoredWrites = mutableListOf<RestoredWrite>()
        var legacyWriteCount = 0
            private set

        override suspend fun getImportedBookByContentHash(contentHash: String): ImportedBookEntity? =
            existingBook?.takeIf { it.contentHash == contentHash }

        override suspend fun saveRestoredBookWithLibraryMapping(
            book: ImportedBookEntity,
            libraryBook: LibraryBookEntity,
            localBookFile: LocalBookFileEntity,
            transfer: CloudFileTransferEntity,
            position: PositionEntity?,
        ) {
            restoredWrites += RestoredWrite(book, libraryBook, localBookFile, transfer, position)
        }

        override suspend fun upsertImportedBook(book: ImportedBookEntity) {
            legacyWriteCount++
        }

        override suspend fun upsertImportedBookWithLibraryMapping(
            book: ImportedBookEntity,
            mutation: LibraryBookMutation,
        ) {
            legacyWriteCount++
        }
    }

    private class RecordingPositionDatabase(
        private val position: PositionEntity?,
    ) : EmptyPositionDatabase() {
        val lookups = mutableListOf<String>()

        override suspend fun getPositionByLibraryBookId(libraryBookId: String): PositionEntity? {
            lookups += libraryBookId
            return position?.takeIf { it.libraryBookId == libraryBookId }
        }
    }

    private class RecordingMetadataExtractor : EpubMetadataExtractor {
        var calls = 0
        override suspend fun extractMetadata(filePath: String): AppResult<EpubMetadata> = Ok(
            EpubMetadata(
                title = "Restored title",
                author = "Restored author",
                description = null,
                coverBytes = null,
                hasMediaOverlays = false,
                publicationDate = null,
            ),
        ).also { calls++ }
    }

    private class FakeFileStore : BookFileTransferFileStore {
        val files = mutableMapOf<String, ByteArray>()
        override fun stagingPath(transferId: String) = "/staging/$transferId.part"
        override fun importedFilePath(localUuid: String, mediaType: String) = "/imports/${localUuid}_$mediaType.epub"
        override suspend fun exists(path: String) = path in files
        override suspend fun size(path: String) = files[path]?.size?.toLong() ?: 0L
        override fun contentHash(path: String) = sha256(files[path] ?: error("No file at $path")).toHexString()
        override suspend fun truncate(path: String) { files[path] = byteArrayOf() }
        override suspend fun write(path: String, offset: Long, bytes: ByteArray) {
            val current = files[path] ?: byteArrayOf()
            val output = ByteArray(maxOf(current.size.toLong(), offset + bytes.size).toInt())
            current.copyInto(output)
            bytes.copyInto(output, offset.toInt())
            files[path] = output
        }
        override suspend fun moveToImportedStore(stagingPath: String, destinationPath: String) {
            files[destinationPath] = files.remove(stagingPath) ?: error("No staging file")
        }
        override suspend fun writeCover(localUuid: String, bytes: ByteArray) = "/covers/$localUuid.png"
        override suspend fun delete(path: String): Boolean = files.remove(path) != null
    }

    private open class EmptyImportedBooksDatabase : ImportedBooksDatabase {
        override suspend fun upsertImportedBook(book: ImportedBookEntity) = Unit
        override suspend fun upsertImportedBookWithLibraryMapping(book: ImportedBookEntity, mutation: LibraryBookMutation) = Unit
        override suspend fun saveRestoredBookWithLibraryMapping(
            book: ImportedBookEntity,
            libraryBook: LibraryBookEntity,
            localBookFile: LocalBookFileEntity,
            transfer: CloudFileTransferEntity,
            position: PositionEntity?,
        ) = Unit
        override fun getAllImportedBooks(): Flow<List<ImportedBookEntity>> = emptyFlow()
        override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? = null
        override suspend fun getImportedBookByContentHash(contentHash: String): ImportedBookEntity? = null
        override suspend fun deleteImportedBook(uuid: String) = Unit
        override suspend fun deleteAllImportedBooks() = Unit
        override suspend fun getImportedBooksCount() = 0
        override suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String) = Unit
        override suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity> = emptyList()
    }

    private open class EmptyLibraryBooksDatabase : LibraryBooksDatabase {
        override suspend fun upsertLibraryBook(book: LibraryBookEntity) = Unit
        override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) = Unit
        override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = emptyFlow()
        override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = null
        override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? = null
        override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? = null
        override suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) = Unit
        override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) = Unit
        override suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> = emptyList()
        override suspend fun getLocalBookFileByImportedBookUuid(importedBookUuid: String): LocalBookFileEntity? = null
        override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) = Unit
    }

    private open class EmptyPositionDatabase : PositionDatabase {
        override suspend fun upsertPosition(position: PositionEntity) = Unit
        override suspend fun upsertPositionWithMutation(position: PositionEntity, mutation: com.retro99.database.api.sync.SyncOutboxEntry) = Unit
        override suspend fun updateRemoteRevision(bookUuid: String, remoteRevision: Long, expectedLocalGeneration: Long?) = Unit
        override suspend fun upsertRemotePosition(position: PositionEntity) = Unit
        override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? = null
        override suspend fun deleteRemotePosition(bookUuid: String) = Unit
        override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? = null
        override suspend fun getAllPositions(): List<PositionEntity> = emptyList()
        override suspend fun deletePosition(bookUuid: String) = Unit
        override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = emptyFlow()
        override fun observeAllPositions(): Flow<List<PositionEntity>> = emptyFlow()
        override suspend fun clearAllData() = Unit
    }

    private fun downloadTransfer(libraryBookId: String, sizeBytes: Long) = CloudFileTransferEntity(
        transferId = "download-1",
        serverId = "parrot-cloud",
        direction = "download",
        libraryBookId = libraryBookId,
        cloudBookId = "cloud-book",
        cloudBookFileId = "cloud-book-file",
        mediaType = "ebook",
        localSourceUuid = "local-restored",
        stagingPath = "/staging/download-1.part",
        sizeBytes = sizeBytes,
        bytesTransferred = sizeBytes,
        contentHash = "hash",
        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
        uploadId = null,
        storagePath = null,
        tusUploadUrl = null,
        tusExpiresAt = null,
        rightsAttestation = null,
        state = "verifying",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
        createdAt = "before",
        updatedAt = "before",
    )

    private fun request(hash: String, sizeBytes: Long) = BookFileDownloadRequest(
        transferId = "download-1",
        serverId = "parrot-cloud",
        libraryBookId = "$CONTENT_HASH_ALGORITHM:$hash",
        cloudBookId = "cloud-book",
        cloudBookFileId = "cloud-book-file",
        mediaType = "ebook",
        fileName = "restored.epub",
        sizeBytes = sizeBytes,
        contentHash = hash,
        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
    )

    private fun importedBook(hash: String, size: Long, filePath: String) = ImportedBookLocalModel(
        uuid = "existing-local",
        title = "Existing book",
        author = "Existing author",
        description = null,
        coverPath = null,
        filePath = filePath,
        fileSize = size,
        contentHash = hash,
        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
        importedAt = "before",
        lastOpenedAt = null,
        bookType = "ebook",
        publicationDate = null,
    )

    private fun libraryBook(hash: String) = object : LibraryBookEntity {
        override val libraryBookId = "$CONTENT_HASH_ALGORITHM:$hash"
        override val contentHash = hash
        override val contentHashAlgorithm = CONTENT_HASH_ALGORITHM
        override val title = "Library title"
        override val author: String? = null
        override val format = "ebook"
        override val cloudBookId = "cloud-book"
        override val remoteRevision = 7L
    }

    private fun position(bookUuid: String, libraryBookId: String) = object : PositionEntity {
        override val bookUuid = bookUuid
        override val libraryBookId = libraryBookId
        override val localGeneration = 3L
        override val remoteRevision = 5L
        override val timestamp = 123L
        override val createdAt = "created"
        override val updatedAt = "updated"
        override val locatorHref = "chapter.xhtml"
        override val locatorType = "application/xhtml+xml"
        override val locatorTitle = "Chapter"
        override val locatorTarget = 2
        override val audioTimestampMs = 100L
        override val chapterIndex = 4
        override val progression = 0.5
        override val totalChapters = 8
        override val totalDurationMs = 1000L
        override val totalProgression = 0.25
        override val position = 17
    }
}
