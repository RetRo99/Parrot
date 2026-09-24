package com.retro99.books.data.transfer

import com.retro99.books.domain.BookFileTransferTransport
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.BookFileDeletionTransport
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
import kotlin.time.Clock
import kotlin.time.Instant

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

    @Test
    fun deleteRemoteBackupRemovesItsRestoredReplicaAndLocalTransferState() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "completed",
            localSourceUuid = "restored-book",
            stagingPath = null,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = 512,
            contentHash = "hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val fileStore = FakeTransferFileStore().apply {
            files["/imports/restored-book.epub"] = "restored".encodeToByteArray()
        }
        val importedBooksDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? = null
        }
        val deletionTransport = RecordingDeletionTransport()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            deletionTransports = listOf(deletionTransport),
            fileStore = fileStore,
        )

        assertTrue(engine.supportsDeletion(SERVER_ID))
        engine.deleteRemoteBackup(SERVER_ID, LIBRARY_BOOK_ID, "ebook")
        engine.deleteRemoteBackup(SERVER_ID, LIBRARY_BOOK_ID, "ebook")

        assertEquals("cloud-file", deletionTransport.deletedFileId)
        assertNull(database.getTransfer(TRANSFER_ID))
        assertTrue(database.fileStates.isEmpty())
        assertTrue("/imports/restored-book.epub" !in fileStore.files)
    }

    @Test
    fun deleteRemoteBackupCanClearAnUploadFailedFileState() = runTest {
        val transfer = activeTransfer().copy(
            state = "failed",
            lastError = "file_exists",
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "upload_failed",
            sizeBytes = 512,
            contentHash = "previous-hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 0,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val deletionTransport = RecordingDeletionTransport()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            deletionTransports = listOf(deletionTransport),
            fileStore = FakeTransferFileStore(),
        )

        engine.deleteRemoteBackup(SERVER_ID, LIBRARY_BOOK_ID, "ebook")
        engine.deleteRemoteBackup(SERVER_ID, LIBRARY_BOOK_ID, "ebook")

        assertEquals("cloud-file", deletionTransport.deletedFileId)
        assertTrue(database.fileStates.isEmpty())
        assertEquals("failed", database.getTransfer(TRANSFER_ID)?.state)
    }

    @Test
    fun retryCanForceAPendingTransferThroughItsBackoff() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "pending",
            localSourceUuid = "restored-book",
            stagingPath = "/staging/transfer.part",
            sizeBytes = 6,
            bytesTransferred = 2,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            attemptCount = 7,
            nextAttemptAt = "2999-01-01T00:00:00Z",
            lastError = "network unavailable",
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = 6,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val fileStore = FakeTransferFileStore().apply {
            files["/staging/transfer.part"] = "ab".encodeToByteArray()
        }
        val transport = ResumingDownloadTransport()
        val finalized = CompletableDeferred<Unit>()
        val finalizer = object : DownloadTransferFinalizer {
            override suspend fun finalize(
                transfer: CloudFileTransferEntity,
                request: BookFileDownloadRequest,
            ): CloudFileTransferEntity {
                val completed = transfer.copy(
                    state = "completed",
                    stagingPath = null,
                    bytesTransferred = request.sizeBytes,
                )
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

        engine.retry(TRANSFER_ID)
        finalized.await()

        val retried = database.getTransfer(TRANSFER_ID)
        assertEquals(2L, transport.requestedOffset)
        assertEquals("completed", retried?.state)
        assertEquals(0, retried?.attemptCount)
        assertNull(retried?.nextAttemptAt)
    }

    @Test
    fun transientFailuresBecomeFailedAfterTheAttemptLimit() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "pending",
            localSourceUuid = "restored-book",
            stagingPath = "/staging/transfer.part",
            sizeBytes = 6,
            bytesTransferred = 0,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            attemptCount = 9,
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = 6,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            downloadTransports = listOf(FailingDownloadTransport()),
            downloadFinalizer = object : DownloadTransferFinalizer {
                override suspend fun finalize(
                    transfer: CloudFileTransferEntity,
                    request: BookFileDownloadRequest,
                ): CloudFileTransferEntity = error("Finalization is not expected after a failed download")
            },
            fileStore = FakeTransferFileStore(),
        )

        engine.recoverPendingTransfers()
        val failed = database.failedTransfer.await()

        assertEquals("failed", failed.state)
        assertEquals(10, failed.attemptCount)
        assertEquals("network unavailable", failed.lastError)
        assertNull(failed.nextAttemptAt)
    }

    @Test
    fun localBackoffStartsAtOneSecond() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "pending",
            localSourceUuid = "restored-book",
            stagingPath = "/staging/transfer.part",
            sizeBytes = 6,
            bytesTransferred = 0,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = 6,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            downloadTransports = listOf(FailingDownloadTransport()),
            downloadFinalizer = object : DownloadTransferFinalizer {
                override suspend fun finalize(
                    transfer: CloudFileTransferEntity,
                    request: BookFileDownloadRequest,
                ): CloudFileTransferEntity = error("Finalization is not expected after a failed download")
            },
            fileStore = FakeTransferFileStore(),
        )

        val beforeRetry = Clock.System.now().toEpochMilliseconds()
        engine.processTransfer(TRANSFER_ID)
        val pending = requireNotNull(database.getTransfer(TRANSFER_ID))
        val afterRetry = Clock.System.now().toEpochMilliseconds()
        val scheduledAt = Instant.parse(requireNotNull(pending.nextAttemptAt)).toEpochMilliseconds()

        assertEquals("pending", pending.state)
        assertEquals(1, pending.attemptCount)
        assertEquals("network unavailable", pending.lastError)
        assertTrue(scheduledAt in (beforeRetry + 1_000L)..(afterRetry + 1_000L))
    }

    @Test
    fun quotaRejectionUsesServerRetryAfter() = runTest {
        val transfer = activeTransfer().copy(
            state = "pending",
            bytesTransferred = 0,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            rightsAttestation = """{"attested_at":"2026-09-24T00:00:00Z","tos_version":"parrot-cloud-backup-tos-2026-09-24.1","attestation_version":"attest-rights-v1"}""",
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "upload_pending",
            sizeBytes = 512,
            contentHash = "hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 0,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val importedBook = testImportedBook()
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String) = importedBook.takeIf { it.uuid == uuid }
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = listOf(
                ReservationRetryTransport(
                    UploadReservationResult.Rejected(
                        reason = "quota_exceeded",
                        usedBytes = 1_000,
                        quotaBytes = 1_000,
                        retryAfterMillis = 5_000,
                    ),
                ),
            ),
        )

        val beforeRetry = Clock.System.now().toEpochMilliseconds()
        engine.processTransfer(TRANSFER_ID)
        val pending = requireNotNull(database.getTransfer(TRANSFER_ID))
        val afterRetry = Clock.System.now().toEpochMilliseconds()
        val scheduledAt = Instant.parse(requireNotNull(pending.nextAttemptAt)).toEpochMilliseconds()

        assertEquals("pending", pending.state)
        assertEquals(1, pending.attemptCount)
        assertEquals("quota_exceeded", pending.lastError)
        assertTrue(scheduledAt in (beforeRetry + 5_000L)..(afterRetry + 5_000L))
    }

    @Test
    fun uploadHashMismatchFailsPermanently() = runTest {
        val transfer = activeTransfer().copy(
            state = "pending",
            bytesTransferred = 0,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            rightsAttestation = """{"attested_at":"2026-09-24T00:00:00Z","tos_version":"parrot-cloud-backup-tos-2026-09-24.1","attestation_version":"attest-rights-v1"}""",
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "upload_pending",
            sizeBytes = 512,
            contentHash = "hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 0,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val importedBook = testImportedBook()
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String) = importedBook.takeIf { it.uuid == uuid }
        }
        val transport = HashMismatchedUploadTransport()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = listOf(transport),
        )

        assertTrue(engine.supportsUpload(SERVER_ID))
        assertEquals(listOf(transfer), database.getTransfers(SERVER_ID, listOf("pending")))
        engine.processTransfer(TRANSFER_ID)
        val result = requireNotNull(database.getTransfer(TRANSFER_ID))

        assertEquals("failed", result.state, "lastError=${result.lastError}")
        assertEquals("verify_failed", result.lastError)
        assertEquals(0, result.attemptCount)
        assertNull(result.nextAttemptAt)
    }

    @Test
    fun invalidatingCloudFileDoesNotDeleteAnImportedOriginal() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "completed",
            localSourceUuid = "original-book",
            stagingPath = null,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        )
        val database = FakeCloudFilesDatabase(transfer, CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "book.epub",
            status = "deleting",
            sizeBytes = 512,
            contentHash = "hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 2,
            updatedAt = "before",
        ))
        val original = object : ImportedBookEntity {
            override val uuid = "original-book"
            override val title = "Original"
            override val author: String? = null
            override val description: String? = null
            override val coverPath: String? = null
            override val filePath = "/imports/original-book.epub"
            override val fileSize = 512L
            override val contentHash: String? = "hash"
            override val contentHashAlgorithm: String? = "sha-256-v1"
            override val importedAt = "before"
            override val lastOpenedAt: String? = null
            override val bookType = "ebook"
            override val publicationDate: String? = null
            override val origin = "import"
        }
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String) = original.takeIf { it.uuid == uuid }
        }
        val fileStore = FakeTransferFileStore().apply {
            files[original.filePath] = "original".encodeToByteArray()
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            fileStore = fileStore,
        )

        engine.invalidateCloudFile("cloud-file")

        assertTrue(original.filePath in fileStore.files)
        assertNull(database.getTransfer(TRANSFER_ID))
    }

    private class FakeCloudFilesDatabase(
        initialTransfer: CloudFileTransferEntity,
        initialFileState: CloudBookFileEntity,
    ) : CloudFilesDatabase {
        private val transfers = mutableMapOf(initialTransfer.transferId to initialTransfer)
        val fileStates = mutableListOf(initialFileState)
        val failedTransfer = CompletableDeferred<CloudFileTransferEntity>()

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
            if (transfer.state == "failed") failedTransfer.complete(transfer)
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
            onHashReset: suspend () -> Unit,
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

    private class FailingDownloadTransport : BookFileDownloadTransport {
        override val serverId: String = SERVER_ID
        override val supportsDownload: Boolean = true

        override suspend fun download(
            request: BookFileDownloadRequest,
            resumeOffset: Long,
            onResponseOffset: suspend (offset: Long) -> Unit,
            onChunk: suspend (bytes: ByteArray) -> Unit,
        ) {
            error("network unavailable")
        }
    }

    private class HashMismatchedUploadTransport : BookFileTransferTransport {
        override val serverId: String = SERVER_ID
        override val capabilities = TransferTransportCapabilities(
            resumeMode = TransferResumeMode.ByteOffset,
            supportsClientSuppliedId = false,
            supportsReplaceInPlace = false,
            supportsUpload = true,
        )

        override suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult =
            UploadReservationResult.Reserved(
                UploadReservation(
                    uploadId = "upload-1",
                    cloudBookFileId = "cloud-file",
                    storagePath = "users/test/books/test/file",
                    uploadEndpoint = "https://cloud.example/upload",
                ),
            )

        override suspend fun upload(
            request: BookFileUploadRequest,
            reservation: UploadReservation,
            resumeUrl: String?,
            resumeOffset: Long,
            onSession: suspend (url: String, expiresAt: String?) -> Unit,
            onHashReset: suspend () -> Unit,
            onChunkHashed: suspend (bytes: ByteArray) -> Unit,
            onProgress: suspend (bytesTransferred: Long) -> Unit,
        ): UploadSessionResult {
            onSession("https://cloud.example/session", null)
            onChunkHashed("different file contents".encodeToByteArray())
            onProgress(request.sizeBytes)
            return UploadSessionResult(
                uploadUrl = "https://cloud.example/session",
                expiresAt = null,
                bytesTransferred = request.sizeBytes,
            )
        }

        override suspend fun finalize(
            reservation: UploadReservation,
            request: BookFileUploadRequest,
        ): CloudBookFileRecord = error("Finalization is not expected after a hash mismatch")

        override suspend fun cancel(reservation: UploadReservation?, resumeUrl: String?) = Unit
    }

    private class ReservationRetryTransport(
        private val result: UploadReservationResult,
    ) : BookFileTransferTransport {
        override val serverId: String = SERVER_ID
        override val capabilities = TransferTransportCapabilities(
            resumeMode = TransferResumeMode.ByteOffset,
            supportsClientSuppliedId = false,
            supportsReplaceInPlace = false,
            supportsUpload = true,
        )

        override suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult = result

        override suspend fun upload(
            request: BookFileUploadRequest,
            reservation: UploadReservation,
            resumeUrl: String?,
            resumeOffset: Long,
            onSession: suspend (url: String, expiresAt: String?) -> Unit,
            onHashReset: suspend () -> Unit,
            onChunkHashed: suspend (bytes: ByteArray) -> Unit,
            onProgress: suspend (bytesTransferred: Long) -> Unit,
        ): UploadSessionResult = error("Upload is not expected after a rejected reservation")

        override suspend fun finalize(
            reservation: UploadReservation,
            request: BookFileUploadRequest,
        ): CloudBookFileRecord = error("Finalization is not expected after a rejected reservation")

        override suspend fun cancel(reservation: UploadReservation?, resumeUrl: String?) = Unit
    }

    private class RecordingDeletionTransport : BookFileDeletionTransport {
        override val serverId: String = SERVER_ID
        var deletedFileId: String? = null

        override suspend fun delete(cloudBookFileId: String) {
            deletedFileId = cloudBookFileId
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

    private fun testImportedBook(): ImportedBookEntity = object : ImportedBookEntity {
        override val uuid = "local-book"
        override val title = "Book"
        override val author: String? = null
        override val description: String? = null
        override val coverPath: String? = null
        override val filePath = "/imports/local-book.epub"
        override val fileSize = 512L
        override val contentHash: String? = "hash"
        override val contentHashAlgorithm: String? = "sha-256-v1"
        override val importedAt = "before"
        override val lastOpenedAt: String? = null
        override val bookType = "EBOOK"
        override val publicationDate: String? = null
        override val origin = "import"
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
