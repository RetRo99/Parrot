package com.retro99.books.data.transfer

import com.retro99.books.domain.BookFileTransferTransport
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.BookFileUploadRequest
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.TransferResumeMode
import com.retro99.books.domain.TransferTransportCapabilities
import com.retro99.books.domain.UploadReservation
import com.retro99.books.domain.UploadReservationResult
import com.retro99.books.domain.UploadSessionResult
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBookMutation
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.books.PositionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookFileTransferEngineTest {
    @Test
    fun downloadResumesFromDurablePartAndFinalizesReplica() = runTest {
        val state = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "ebook",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = 6,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
            updatedAt = "now",
        )
        val database = FakeCloudFilesDatabase(activeTransfer().copy(
            direction = "download",
            state = "failed",
            localSourceUuid = "restored-book",
            stagingPath = "/staging/transfer.part",
            sizeBytes = 6,
            bytesTransferred = 2,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        ), state)
        val fileStore = FakeTransferFileStore().apply { files["/staging/transfer.part"] = "ab".encodeToByteArray() }
        val transport = ResumingDownloadTransport()
        val finalized = CompletableDeferred<Unit>()
        val finalizer = object : DownloadTransferFinalizer {
            override suspend fun finalize(
                transfer: CloudFileTransferEntity,
                request: BookFileDownloadRequest,
            ): CloudFileTransferEntity {
                assertEquals("restored-book", transfer.localSourceUuid)
                assertEquals("abcdef", fileStore.files.getValue("/staging/transfer.part").decodeToString())
                val completed = transfer.copy(state = "completed", stagingPath = null, bytesTransferred = 6)
                database.updateTransfer(completed)
                finalized.complete(Unit)
                return completed
            }
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            downloadTransports = listOf(transport),
            downloadFinalizer = finalizer,
            fileStore = fileStore,
        )

        val transferId = engine.enqueueDownload(SERVER_ID, LIBRARY_BOOK_ID, "ebook")
        finalized.await()

        val result = database.getTransfer(transferId)
        assertEquals("completed", result?.state)
        assertEquals(2L, transport.requestedOffset)
        assertEquals("abcdef", fileStore.files.getValue("/staging/transfer.part").decodeToString())
        assertTrue(database.fileStates.any { it.status == "available" })
    }

    @Test
    fun cancelPersistsLocalTerminalStateBeforeBestEffortRemoteCleanup() = runTest {
        val transfer = activeTransfer()
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "uploading",
            sizeBytes = 512,
            contentHash = "hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 0,
            updatedAt = "before",
        )
        val filesDatabase = FakeCloudFilesDatabase(transfer, fileState)
        val transport = CheckingCancelTransport(filesDatabase)
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = filesDatabase,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = listOf(transport),
        )

        engine.cancel(SERVER_ID, LIBRARY_BOOK_ID)

        val cancelled = filesDatabase.getTransfer(transfer.transferId)
        assertEquals("cancelled", cancelled?.state)
        assertEquals(0, cancelled?.bytesTransferred)
        assertNull(filesDatabase.fileStates.singleOrNull())
        assertEquals("reservation-1", transport.cancelledReservationId)
    }

    private class FakeCloudFilesDatabase(
        initialTransfer: CloudFileTransferEntity,
        initialFileState: CloudBookFileEntity,
    ) : CloudFilesDatabase {
        private val transfers = mutableMapOf(initialTransfer.transferId to initialTransfer)
        val fileStates = mutableListOf(initialFileState)

        override suspend fun upsertFileState(file: CloudBookFileEntity) {
            fileStates.removeAll {
                it.libraryBookId == file.libraryBookId &&
                    it.mediaType == file.mediaType &&
                    it.relativePath == file.relativePath
            }
            fileStates += file
        }

        override suspend fun getFileStates(libraryBookId: String): List<CloudBookFileEntity> =
            fileStates.filter { it.libraryBookId == libraryBookId }

        override fun observeFileStates(): Flow<List<CloudBookFileEntity>> = flowOf(fileStates.toList())

        override suspend fun deleteFileState(libraryBookId: String, mediaType: String, relativePath: String) {
            fileStates.removeAll {
                it.libraryBookId == libraryBookId && it.mediaType == mediaType && it.relativePath == relativePath
            }
        }

        override suspend fun insertTransfer(transfer: CloudFileTransferEntity) {
            transfers[transfer.transferId] = transfer
        }

        override suspend fun getTransfer(transferId: String): CloudFileTransferEntity? = transfers[transferId]

        override suspend fun updateTransfer(transfer: CloudFileTransferEntity) {
            transfers[transfer.transferId] = transfer
        }

        override suspend fun deleteTransfer(transferId: String) {
            transfers.remove(transferId)
        }

        override suspend fun getTransfers(serverId: String, states: List<String>): List<CloudFileTransferEntity> =
            transfers.values.filter { it.serverId == serverId && it.state in states }

        override fun observeTransfers(serverId: String, libraryBookId: String): Flow<List<CloudFileTransferEntity>> =
            flowOf(transfers.values.filter { it.serverId == serverId && it.libraryBookId == libraryBookId })

        override fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> = flowOf(transfers.values.toList())

        override fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> = flowOf(transfers.values.toList())

        override suspend fun clearAllData() {
            transfers.clear()
            fileStates.clear()
        }
    }

    private class CheckingCancelTransport(
        private val database: FakeCloudFilesDatabase,
    ) : BookFileTransferTransport {
        override val serverId: String = SERVER_ID
        override val capabilities = TransferTransportCapabilities(
            resumeMode = TransferResumeMode.ByteOffset,
            supportsClientSuppliedId = false,
            supportsReplaceInPlace = false,
            supportsUpload = true,
        )
        var cancelledReservationId: String? = null
            private set

        override suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult =
            error("reserve is not used by cancellation")

        override suspend fun upload(
            request: BookFileUploadRequest,
            reservation: UploadReservation,
            resumeUrl: String?,
            resumeOffset: Long,
            onSession: suspend (url: String, expiresAt: String?) -> Unit,
            onChunkHashed: suspend (bytes: ByteArray) -> Unit,
            onProgress: suspend (bytesTransferred: Long) -> Unit,
        ): UploadSessionResult = error("upload is not used by cancellation")

        override suspend fun finalize(
            reservation: UploadReservation,
            request: BookFileUploadRequest,
        ): CloudBookFileRecord = error("finalize is not used by cancellation")

        override suspend fun cancel(reservation: UploadReservation?, resumeUrl: String?) {
            assertEquals("cancelled", database.getTransfer(TRANSFER_ID)?.state)
            cancelledReservationId = reservation?.uploadId
            error("remote cleanup is temporarily unavailable")
        }
    }

    private class ResumingDownloadTransport : BookFileDownloadTransport {
        override val serverId: String = SERVER_ID
        override val supportsDownload: Boolean = true
        var requestedOffset: Long? = null

        override suspend fun download(
            request: BookFileDownloadRequest,
            resumeOffset: Long,
            onResponseOffset: suspend (offset: Long) -> Unit,
            onChunk: suspend (bytes: ByteArray) -> Unit,
        ) {
            requestedOffset = resumeOffset
            onResponseOffset(resumeOffset)
            onChunk("cdef".encodeToByteArray())
        }
    }

    private class FakeTransferFileStore : BookFileTransferFileStore {
        val files = mutableMapOf<String, ByteArray>()
        override fun stagingPath(transferId: String) = "/staging/$transferId.part"
        override fun importedFilePath(localUuid: String, mediaType: String) = "/imports/$localUuid.epub"
        override suspend fun exists(path: String) = path in files
        override suspend fun size(path: String) = files[path]?.size?.toLong() ?: 0L
        override suspend fun truncate(path: String) { files[path] = byteArrayOf() }
        override suspend fun write(path: String, offset: Long, bytes: ByteArray) {
            val current = files[path] ?: byteArrayOf()
            val result = ByteArray(maxOf(current.size.toLong(), offset + bytes.size).toInt())
            current.copyInto(result)
            bytes.copyInto(result, offset.toInt())
            files[path] = result
        }
        override suspend fun moveToImportedStore(stagingPath: String, destinationPath: String) {
            files[destinationPath] = files.remove(stagingPath) ?: error("No staging file")
        }
        override suspend fun writeCover(localUuid: String, bytes: ByteArray) = "/covers/$localUuid.png"
        override suspend fun delete(path: String): Boolean = files.remove(path) != null
    }

    private object UnusedImportedBooksDatabase : ImportedBooksDatabase {
        override suspend fun upsertImportedBook(book: ImportedBookEntity) = error("Unused")
        override suspend fun upsertImportedBookWithLibraryMapping(
            book: ImportedBookEntity,
            mutation: LibraryBookMutation,
        ) = error("Unused")
        override suspend fun saveRestoredBookWithLibraryMapping(
            book: ImportedBookEntity,
            libraryBook: LibraryBookEntity,
            localBookFile: LocalBookFileEntity,
            transfer: CloudFileTransferEntity,
            position: PositionEntity?,
        ) = error("Unused")
        override fun getAllImportedBooks(): Flow<List<ImportedBookEntity>> = emptyFlow()
        override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? = error("Unused")
        override suspend fun getImportedBookByContentHash(contentHash: String): ImportedBookEntity? = error("Unused")
        override suspend fun deleteImportedBook(uuid: String) = error("Unused")
        override suspend fun deleteAllImportedBooks() = error("Unused")
        override suspend fun getImportedBooksCount(): Int = error("Unused")
        override suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String) = error("Unused")
        override suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity> = error("Unused")
    }

    private object UnusedLibraryBooksDatabase : LibraryBooksDatabase {
        override suspend fun upsertLibraryBook(book: LibraryBookEntity) = error("Unused")
        override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) = error("Unused")
        override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = emptyFlow()
        override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = error("Unused")
        override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? = error("Unused")
        override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? = error("Unused")
        override suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) = error("Unused")
        override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) = error("Unused")
        override suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> = error("Unused")
        override suspend fun getLocalBookFileByImportedBookUuid(importedBookUuid: String): LocalBookFileEntity? =
            error("Unused")
        override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) = error("Unused")
    }

    private companion object {
        const val SERVER_ID = "test-cloud"
        const val LIBRARY_BOOK_ID = "sha-256-v1:expected-hash"
        const val TRANSFER_ID = "transfer-1"

        fun activeTransfer() = CloudFileTransferEntity(
            transferId = TRANSFER_ID,
            serverId = SERVER_ID,
            direction = "upload",
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            localSourceUuid = "local-book",
            stagingPath = null,
            sizeBytes = 512,
            bytesTransferred = 128,
            contentHash = "hash",
            contentHashAlgorithm = "sha-256-v1",
            uploadId = "reservation-1",
            storagePath = "object-path",
            tusUploadUrl = "https://cloud.example/session",
            tusExpiresAt = null,
            rightsAttestation = null,
            state = "transferring",
            attemptCount = 0,
            nextAttemptAt = null,
            lastError = null,
            createdAt = "before",
            updatedAt = "before",
        )
    }
}
