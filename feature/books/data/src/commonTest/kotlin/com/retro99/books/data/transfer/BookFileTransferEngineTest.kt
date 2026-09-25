package com.retro99.books.data.transfer

import com.retro99.books.domain.BookFileTransferTransport
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.BookFileTransferSessionExpiredException
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.BookFileDeletionTransport
import com.retro99.books.domain.BookFileUploadRequest
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.LocalBookFileUsageCoordinator
import com.retro99.books.domain.TransferResumeMode
import com.retro99.books.domain.TransferTransportCapabilities
import com.retro99.books.domain.UploadReservation
import com.retro99.books.domain.UploadReservationResult
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.books.domain.UploadSessionResult
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.cloudfiles.PendingCloudFileFeedChange
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBookMutation
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.api.library.LibraryBackfillResult
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.RemoteReplicaRetirementResult
import com.retro99.database.api.books.PositionEntity
import com.retro99.base.result.AppResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.user.api.UserRegistry
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.sha256
import com.retro99.books.data.toHexString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.retro99.sync.domain.SyncPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            serverId = "parrot-cloud",
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
        database.fileStates.add(
            0,
            state.copy(
                cloudBookId = "another-cloud-book",
                cloudBookFileId = "another-cloud-file",
                contentHash = "another-hash",
            ),
        )
        val fileStore = FakeTransferFileStore().apply { files["/staging/transfer.part"] = "ab".encodeToByteArray() }
        val transport = ResumingDownloadTransport(serverId = "parrot-cloud")
        val finalized = CompletableDeferred<Unit>()
        val evidenceDatabase = RecordingLibraryEvidenceDatabase()
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
            libraryEvidenceDatabase = evidenceDatabase,
            authenticatedRepositoryProvider = authenticatedRepositoryProvider(),
        )

        val transferId = engine.enqueueDownloadForCloudFile(
            serverId = "parrot-cloud",
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file",
        )
        finalized.await()
        evidenceDatabase.recorded.await()

        val result = database.getTransfer(transferId)
        assertEquals("completed", result?.state)
        assertEquals("cloud-file", result?.cloudBookFileId)
        assertEquals("cloud-file", transport.requestedFileId)
        assertEquals(2L, transport.requestedOffset)
        assertEquals("abcdef", fileStore.files.getValue("/staging/transfer.part").decodeToString())
        assertTrue(database.fileStates.any { it.status == "available" })
        val evidence = evidenceDatabase.records.values.single()
            .evidence as SourceIdentityEvidence.CompletedTransfer
        assertEquals("cloud-file", evidence.source.nativeResourceId)
        assertEquals("restored-book", evidence.destination.nativeResourceId)
        assertEquals(
            SourceAccountIdentity.Portable("parrot-cloud", "cloud-account"),
            evidence.source.book.accountIdentity,
        )
    }

    @Test
    fun exactCloudFileDownloadRejectsMissingOrMismatchedFileWithoutFallback() = runTest {
        val selectedFile = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "selected-cloud-file",
            mediaType = "ebook",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = 6L,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1L,
            updatedAt = "now",
        )
        val database = FakeCloudFilesDatabase(initialFileState = selectedFile)
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            downloadTransports = listOf(ResumingDownloadTransport(SERVER_ID)),
        )

        assertFailsWith<IllegalArgumentException> {
            engine.enqueueDownloadForCloudFile(
                serverId = SERVER_ID,
                libraryBookId = LIBRARY_BOOK_ID,
                cloudBookId = "cloud-book",
                cloudBookFileId = "missing-cloud-file",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            engine.enqueueDownloadForCloudFile(
                serverId = SERVER_ID,
                libraryBookId = LIBRARY_BOOK_ID,
                cloudBookId = "different-cloud-book",
                cloudBookFileId = "selected-cloud-file",
            )
        }

        assertTrue(database.insertedTransfers.isEmpty())
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

        assertNull(filesDatabase.getTransfer(transfer.transferId))
        assertNull(filesDatabase.fileStates.singleOrNull())
        assertEquals("reservation-1", transport.cancelledReservationId)
    }

    @Test
    fun deleteRemoteBackupPreservesItsRestoredReplicaAndTransferState() = runTest {
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
        assertEquals("completed", database.getTransfer(TRANSFER_ID)?.state)
        assertTrue(database.fileStates.isEmpty())
        assertTrue("/imports/restored-book.epub" in fileStore.files)
    }

    @Test
    fun deleteRemoteCloudFileCancelsExactActiveDownloadsAndKeepsRestoredCopy() = runTest {
        val firstFile = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = "cloud-file-first",
            mediaType = "EBOOK",
            relativePath = "",
            fileName = "first.epub",
            status = "available",
            sizeBytes = 512,
            contentHash = "hash",
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
            updatedAt = "before",
        )
        val selectedFile = firstFile.copy(
            cloudBookFileId = "cloud-file-selected",
            relativePath = "selected.epub",
            fileName = "selected.epub",
        )
        val database = FakeCloudFilesDatabase(initialFileState = firstFile).apply {
            fileStates += selectedFile
        }
        val selectedActiveDownload = activeTransfer().copy(
            transferId = "active-selected-download",
            direction = "download",
            cloudBookFileId = selectedFile.cloudBookFileId,
            mediaType = selectedFile.mediaType,
            localSourceUuid = "selected-download",
            stagingPath = "/staging/active-selected-download.part",
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            state = "transferring",
        )
        val otherActiveDownload = selectedActiveDownload.copy(
            transferId = "active-other-download",
            cloudBookFileId = firstFile.cloudBookFileId,
            stagingPath = "/staging/active-other-download.part",
        )
        val completedDownload = selectedActiveDownload.copy(
            transferId = "completed-selected-download",
            localSourceUuid = "restored-book",
            stagingPath = null,
            state = "completed",
            bytesTransferred = selectedFile.sizeBytes,
        )
        database.insertTransfer(selectedActiveDownload)
        database.insertTransfer(otherActiveDownload)
        database.insertTransfer(completedDownload)
        val fileStore = FakeTransferFileStore().apply {
            files["/imports/restored-book.epub"] = "restored copy".encodeToByteArray()
            files["/staging/active-selected-download.part"] = "partial".encodeToByteArray()
            files["/staging/active-other-download.part"] = "other".encodeToByteArray()
        }
        val deletionTransport = RecordingDeletionTransport()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            deletionTransports = listOf(deletionTransport),
            fileStore = fileStore,
        )

        engine.deleteRemoteCloudFile(
            serverId = SERVER_ID,
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookId = "cloud-book",
            cloudBookFileId = selectedFile.cloudBookFileId,
            mediaType = selectedFile.mediaType,
        )

        assertEquals(selectedFile.cloudBookFileId, deletionTransport.deletedFileId)
        assertEquals(listOf(firstFile), database.fileStates)
        assertNull(database.getTransfer(selectedActiveDownload.transferId))
        assertEquals("transferring", database.getTransfer(otherActiveDownload.transferId)?.state)
        assertEquals("completed", database.getTransfer(completedDownload.transferId)?.state)
        assertTrue("/imports/restored-book.epub" in fileStore.files)
        assertFalse("/staging/active-selected-download.part" in fileStore.files)
        assertTrue("/staging/active-other-download.part" in fileStore.files)
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

        assertEquals(TRANSFER_ID, engine.enqueueDownload(SERVER_ID, LIBRARY_BOOK_ID, "ebook"))
        assertEquals("2999-01-01T00:00:00Z", database.getTransfer(TRANSFER_ID)?.nextAttemptAt)
        assertNull(transport.requestedOffset)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun invalidatingCloudFileDeletesOnlyItsCloudDownloadedReplica() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "completed",
            localSourceUuid = "restored-book",
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
            sizeBytes = 8,
            contentHash = "hash",
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            remoteRevision = 2,
            updatedAt = "before",
        ))
        val restored = object : ImportedBookEntity {
            override val uuid = "restored-book"
            override val title = "Restored"
            override val author: String? = null
            override val description: String? = null
            override val coverPath = "/covers/restored-book.png"
            override val filePath = "/imports/restored-book.epub"
            override val fileSize = 8L
            override val contentHash: String? = "hash"
            override val contentHashAlgorithm: String? = CONTENT_HASH_ALGORITHM
            override val importedAt = "before"
            override val lastOpenedAt: String? = null
            override val bookType = "ebook"
            override val publicationDate: String? = null
            override val origin = "cloud_download"
            override val cloudBookFileId = "cloud-file"
        }
        val deletedBooks = mutableListOf<String>()
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String) = restored.takeIf { it.uuid == uuid }
            override suspend fun deleteImportedBook(uuid: String) {
                deletedBooks += uuid
            }
        }
        val fileStore = FakeTransferFileStore().apply {
            files[restored.filePath] = "bookdata".encodeToByteArray()
            files[requireNotNull(restored.coverPath)] = byteArrayOf(1, 2)
        }
        val fileUsageCoordinator = LocalBookFileUsageCoordinator()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            fileStore = fileStore,
            fileUsageCoordinator = fileUsageCoordinator,
        )
        val useLease = fileUsageCoordinator.acquireUse(restored.filePath)

        val invalidation = async { engine.invalidateCloudFile("cloud-file") }
        runCurrent()
        assertFalse(invalidation.isCompleted)
        assertTrue(restored.filePath in fileStore.files)

        useLease.release()
        invalidation.await()

        assertEquals(listOf("restored-book"), deletedBooks)
        assertTrue(restored.filePath !in fileStore.files)
        assertTrue(restored.coverPath !in fileStore.files)
        assertNull(database.getTransfer(TRANSFER_ID))
    }

    @Test
    fun invalidationKeepsOwnershipRowsWhenRestoredFileDeletionFails() = runTest {
        assertInvalidationDeleteFailureKeepsOwnershipRows(
            failingPath = "/imports/restored-book.epub",
        )
    }

    @Test
    fun invalidationKeepsOwnershipRowsWhenCoverDeletionFails() = runTest {
        assertInvalidationDeleteFailureKeepsOwnershipRows(
            failingPath = "/covers/restored-book.png",
        )
    }

    @Test
    fun invalidationKeepsOwnershipRowsWhenStagingDeletionFails() = runTest {
        assertInvalidationDeleteFailureKeepsOwnershipRows(
            failingPath = "/staging/transfer-1.part",
            stagingPath = "/staging/transfer-1.part",
        )
    }

    private suspend fun assertInvalidationDeleteFailureKeepsOwnershipRows(
        failingPath: String,
        stagingPath: String? = null,
    ) {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "completed",
            localSourceUuid = "restored-book",
            stagingPath = stagingPath,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        )
        val database = FakeCloudFilesDatabase(transfer)
        val restored = object : ImportedBookEntity {
            override val uuid = "restored-book"
            override val title = "Restored"
            override val author: String? = null
            override val description: String? = null
            override val coverPath = "/covers/restored-book.png"
            override val filePath = "/imports/restored-book.epub"
            override val fileSize = 8L
            override val contentHash: String? = "hash"
            override val contentHashAlgorithm: String? = CONTENT_HASH_ALGORITHM
            override val importedAt = "before"
            override val lastOpenedAt: String? = null
            override val bookType = "ebook"
            override val publicationDate: String? = null
            override val origin = "cloud_download"
            override val cloudBookFileId = "cloud-file"
        }
        val deletedBooks = mutableListOf<String>()
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String) =
                restored.takeIf { it.uuid == uuid }

            override suspend fun deleteImportedBook(uuid: String) {
                deletedBooks += uuid
            }
        }
        val fileStore = FakeTransferFileStore().apply {
            files[restored.filePath] = "bookdata".encodeToByteArray()
            files[requireNotNull(restored.coverPath)] = byteArrayOf(1, 2)
            stagingPath?.let { path -> files[path] = "staged".encodeToByteArray() }
            failedDeletes += failingPath
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = emptyList(),
            fileStore = fileStore,
        )

        assertFailsWith<IllegalStateException> {
            engine.invalidateCloudFile("cloud-file")
        }

        assertTrue(failingPath in fileStore.files)
        assertTrue(restored.filePath in fileStore.files)
        if (failingPath != restored.filePath) {
            assertTrue(requireNotNull(restored.coverPath) in fileStore.files)
        }
        assertTrue(deletedBooks.isEmpty())
        assertEquals(transfer, database.getTransfer(transfer.transferId))
    }

    @Test
    fun uploadHappyPathCommitsAvailableFileAfterHashVerification() = runTest {
        val bytes = "uploaded epub bytes".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val transfer = uploadTransfer(contentHash, bytes.size.toLong())
        val transport = UploadPathTransport(bytes, uploadedFile(contentHash, bytes.size.toLong()))
        val fixture = uploadFixture(transfer, testImportedBook(contentHash, bytes.size.toLong()), transport)

        fixture.engine.processTransfer(transfer.transferId)

        val completed = requireNotNull(fixture.database.getTransfer(transfer.transferId))
        assertEquals("completed", completed.state)
        assertEquals(bytes.size.toLong(), completed.bytesTransferred)
        assertEquals(1, transport.reserveCalls)
        assertEquals(1, transport.uploadCalls)
        assertEquals(1, transport.finalizeCalls)
        assertEquals("available", fixture.database.fileStates.single().status)
        assertEquals("uploaded-file", fixture.database.fileStates.single().cloudBookFileId)
    }

    @Test
    fun repeatedUploadEnqueueKeepsFutureBackoffUntilExplicitRetry() = runTest {
        val bytes = "enqueue retry source".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val importedBook = testImportedBook(contentHash, bytes.size.toLong())
        val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            attemptCount = 2,
            nextAttemptAt = "2999-01-01T00:00:00Z",
            lastError = "network unavailable",
        )
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
        )
        val fileStore = FakeTransferFileStore().apply { files[importedBook.filePath] = bytes }
        val fixture = uploadFixture(transfer, importedBook, transport, fileStore)

        assertEquals(transfer.transferId, fixture.engine.enqueueUpload(
            SERVER_ID,
            importedBook.uuid,
            UploadRightsAttestation("now", "terms", "rights"),
        ))
        assertEquals("2999-01-01T00:00:00Z", fixture.database.getTransfer(transfer.transferId)?.nextAttemptAt)
        assertEquals(0, transport.reserveCalls)

        fixture.engine.retry(transfer.transferId)
        fixture.database.completedTransfer.await()

        assertEquals(1, transport.reserveCalls)
        assertEquals("completed", fixture.database.getTransfer(transfer.transferId)?.state)
    }

    @Test
    fun enqueueUploadHoldsAUseLeaseWhileHashingTheSource() = runTest {
        // Given
        val bytes = "hash leased source".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val importedBook = testImportedBook(contentHash, bytes.size.toLong())
        val libraryBookId = "$CONTENT_HASH_ALGORITHM:$contentHash"
        val coordinator = LocalBookFileUsageCoordinator()
        val fileStore = FakeTransferFileStore().apply {
            files[importedBook.filePath] = bytes
        }
        var removalWasBlockedDuringHash = false
        fileStore.onContentHash = { path ->
            val removalLease = runBlocking { coordinator.tryAcquireRemoval(path) }
            removalWasBlockedDuringHash = removalLease == null
            removalLease?.release()
        }
        val existingCloudFile = uploadedFile(contentHash, bytes.size.toLong())
        val database = FakeCloudFilesDatabase(
            initialFileState = CloudBookFileEntity(
                libraryBookId = libraryBookId,
                cloudBookId = existingCloudFile.cloudBookId,
                cloudBookFileId = existingCloudFile.cloudBookFileId,
                mediaType = existingCloudFile.mediaType,
                relativePath = "",
                fileName = existingCloudFile.fileName,
                status = "available",
                sizeBytes = existingCloudFile.sizeBytes,
                contentHash = contentHash,
                contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
                remoteRevision = existingCloudFile.remoteRevision,
                updatedAt = "now",
            ),
        )
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String) =
                importedBook.takeIf { candidate -> candidate.uuid == uuid }
        }
        val libraryBook = testLibraryBook(libraryBookId, contentHash)
        val libraryDatabase = object : LibraryBooksDatabase by UnusedLibraryBooksDatabase {
            override suspend fun getLibraryBookById(libraryBookId: String) =
                libraryBook.takeIf { candidate -> candidate.libraryBookId == libraryBookId }
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = libraryDatabase,
            transports = listOf(UploadPathTransport(bytes, existingCloudFile)),
            fileStore = fileStore,
            fileUsageCoordinator = coordinator,
        )

        // When
        val transferId = engine.enqueueUpload(
            SERVER_ID,
            importedBook.uuid,
            UploadRightsAttestation("now", "terms", "rights"),
        )

        // Then
        assertTrue(removalWasBlockedDuringHash)
        assertEquals("completed", database.getTransfer(transferId)?.state)
        assertNotNull(coordinator.tryAcquireRemoval(importedBook.filePath)).release()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun uploadStreamHoldsAUseLeaseUntilTheTransportFinishesReading() = runTest {
        // Given
        val bytes = "stream leased source".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val importedBook = testImportedBook(contentHash, bytes.size.toLong())
        val transfer = uploadTransfer(contentHash, bytes.size.toLong())
        val coordinator = LocalBookFileUsageCoordinator()
        val fileStore = FakeTransferFileStore().apply {
            files[importedBook.filePath] = bytes
        }
        val uploadGate = CompletableDeferred<Unit>()
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
            uploadGate = uploadGate,
        )
        val fixture = uploadFixture(
            transfer,
            importedBook,
            transport,
            fileStore,
            coordinator,
        )
        val processing = async { fixture.engine.processTransfer(transfer.transferId) }

        // When
        runCurrent()

        // Then
        assertEquals(1, transport.uploadCalls)
        assertNull(coordinator.tryAcquireRemoval(importedBook.filePath))

        uploadGate.complete(Unit)
        processing.await()
        assertNotNull(coordinator.tryAcquireRemoval(importedBook.filePath)).release()
    }

    @Test
    fun concurrentEnqueueUploadCallsCreateOneTransferRow() = runTest {
        val bytes = "double tap source".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val importedBook = testImportedBook(contentHash, bytes.size.toLong())
        val database = FakeCloudFilesDatabase()
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String) = importedBook.takeIf { it.uuid == uuid }
        }
        val libraryBook = testLibraryBook("$CONTENT_HASH_ALGORITHM:$contentHash", contentHash)
        val libraryDatabase = object : LibraryBooksDatabase by UnusedLibraryBooksDatabase {
            override suspend fun getLibraryBookById(libraryBookId: String) =
                libraryBook.takeIf { it.libraryBookId == libraryBookId }
        }
        val fileStore = FakeTransferFileStore().apply { files[importedBook.filePath] = bytes }
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
        )
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = libraryDatabase,
            transports = listOf(transport),
            fileStore = fileStore,
        )

        val ids = coroutineScope {
            listOf(
                async { engine.enqueueUpload(SERVER_ID, importedBook.uuid, UploadRightsAttestation("now", "terms", "rights")) },
                async { engine.enqueueUpload(SERVER_ID, importedBook.uuid, UploadRightsAttestation("now", "terms", "rights")) },
            ).map { deferred -> deferred.await() }
        }
        database.completedTransfer.await()

        assertEquals(ids.first(), ids.last())
        assertEquals(1, database.insertedTransfers.size)
    }

    @Test
    fun backupAllSkipsCloudDownloadedReplicasAndCountsPerBookFailures() = runTest {
        val uploadBytes = "imported original".encodeToByteArray()
        val uploadHash = sha256(uploadBytes).toHexString()
        val missingBytes = "not linked yet".encodeToByteArray()
        val missingHash = sha256(missingBytes).toHexString()
        val uploadGate = CompletableDeferred<Unit>()
        val uploadedBook = testImportedBook(
            uploadHash,
            uploadBytes.size.toLong(),
            uuid = "imported-book",
            filePath = "/imports/imported-book.epub",
        )
        val cloudReplica = testImportedBook(
            uploadHash,
            uploadBytes.size.toLong(),
            uuid = "restored-book",
            filePath = "/imports/restored-book.epub",
            origin = "cloud_download",
        )
        val unlinkedImport = testImportedBook(
            missingHash,
            missingBytes.size.toLong(),
            uuid = "unlinked-book",
            filePath = "/imports/unlinked-book.epub",
        )
        val attemptedBooks = mutableListOf<String>()
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override fun getAllImportedBooks() = flowOf(listOf(uploadedBook, cloudReplica, unlinkedImport))
            override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? {
                attemptedBooks += uuid
                return listOf(uploadedBook, cloudReplica, unlinkedImport).firstOrNull { it.uuid == uuid }
            }
        }
        val libraryBook = testLibraryBook("$CONTENT_HASH_ALGORITHM:$uploadHash", uploadHash)
        val libraryDatabase = object : LibraryBooksDatabase by UnusedLibraryBooksDatabase {
            override suspend fun getLibraryBookById(libraryBookId: String) =
                libraryBook.takeIf { it.libraryBookId == libraryBookId }
        }
        val fileStore = FakeTransferFileStore().apply {
            files[uploadedBook.filePath] = uploadBytes
            files[cloudReplica.filePath] = uploadBytes
            files[unlinkedImport.filePath] = missingBytes
        }
        val transport = UploadPathTransport(
            payload = uploadBytes,
            file = uploadedFile(uploadHash, uploadBytes.size.toLong()),
            uploadGate = uploadGate,
        )
        val database = FakeCloudFilesDatabase()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = libraryDatabase,
            transports = listOf(transport),
            fileStore = fileStore,
        )

        val result = engine.backupAll(
            SERVER_ID,
            UploadRightsAttestation("now", "terms", "rights"),
        )
        uploadGate.complete(Unit)
        database.completedTransfer.await()

        assertEquals(BackupAllResult(queuedCount = 1, failedCount = 1), result)
        assertTrue(attemptedBooks.contains("imported-book"))
        assertTrue(attemptedBooks.contains("unlinked-book"))
        assertFalse(attemptedBooks.contains("restored-book"))
    }

    @Test
    fun aggregateReportsMixedDirectionsAndIgnoresTerminalFailures() = runTest {
        val upload = activeTransfer().copy(state = "transferring", direction = "upload")
        val database = FakeCloudFilesDatabase(upload)
        database.insertTransfer(
            upload.copy(
                transferId = "download-transfer",
                direction = "download",
                state = "pending",
                bytesTransferred = 12,
                sizeBytes = 64,
            ),
        )
        database.insertTransfer(upload.copy(transferId = "failed-transfer", state = "failed", lastError = "file_exists"))
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = UnusedImportedBooksDatabase,
            libraryBooksDatabase = UnusedLibraryBooksDatabase,
            transports = listOf(ReservationRetryTransport(UploadReservationResult.Rejected("unused"))),
            downloadTransports = listOf(FailingDownloadTransport()),
        )

        val status = requireNotNull(engine.observe().first())

        assertEquals(SyncPhase.TRANSFERRING_FILES, status.phase)
        assertEquals(2, status.activeItems)
        assertEquals(2, status.totalItems)
        assertNull(status.error)
        assertEquals(false, status.canRetry)
    }

    @Test
    fun serverDedupeCompletesWithoutStartingAnUpload() = runTest {
        val bytes = "deduplicated epub".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val existingFile = uploadedFile(contentHash, bytes.size.toLong(), "deduped-file")
        val transfer = uploadTransfer(contentHash, bytes.size.toLong())
        val transport = UploadPathTransport(
            payload = bytes,
            file = existingFile,
            reservationResult = UploadReservationResult.AlreadyAvailable(existingFile),
        )
        val fixture = uploadFixture(transfer, testImportedBook(contentHash, bytes.size.toLong()), transport)

        fixture.engine.processTransfer(transfer.transferId)

        assertEquals("completed", fixture.database.getTransfer(transfer.transferId)?.state)
        assertEquals(1, transport.reserveCalls)
        assertEquals(0, transport.uploadCalls)
        assertEquals(0, transport.finalizeCalls)
        assertEquals("deduped-file", fixture.database.fileStates.single().cloudBookFileId)
    }

    @Test
    fun `verified Cloud file links a second Local source with recovery evidence`() =
        runTest {
        val bytes = "duplicate imported epub".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val existingFile = uploadedFile(contentHash, bytes.size.toLong(), "shared-cloud-file")
        val completedTransfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            serverId = "parrot-cloud",
            localSourceUuid = "first-local-book",
            cloudBookFileId = existingFile.cloudBookFileId,
            state = "completed",
        )
        val newLocalBook = testImportedBook(
            contentHash = contentHash,
            fileSize = bytes.size.toLong(),
            uuid = "second-local-book",
        )
        val evidenceDatabase = RecordingLibraryEvidenceDatabase()
        val fileStore = FakeTransferFileStore().apply { files[newLocalBook.filePath] = bytes }
        val transport = UploadPathTransport(
            payload = bytes,
            file = existingFile,
            serverId = "parrot-cloud",
        )
        val fixture = uploadFixture(
            transfer = completedTransfer,
            importedBook = newLocalBook,
            transport = transport,
            fileStore = fileStore,
            libraryEvidenceDatabase = evidenceDatabase,
        )
        fixture.database.fileStates[0] = fixture.database.fileStates.single().copy(
            cloudBookFileId = existingFile.cloudBookFileId,
            status = "available",
        )

        val newTransferId = fixture.engine.enqueueUpload(
            serverId = "parrot-cloud",
            localBookUuid = newLocalBook.uuid,
            rightsAttestation = UploadRightsAttestation("now", "terms", "rights"),
        )
        fixture.engine.recoverPendingTransfers()

        assertTrue(newTransferId != completedTransfer.transferId)
        assertEquals("completed", fixture.database.getTransfer(newTransferId)?.state)
        assertEquals(0, transport.reserveCalls)
        assertEquals(0, transport.uploadCalls)
        assertEquals(0, transport.finalizeCalls)
        assertEquals(2, evidenceDatabase.records.size)
        assertTrue(fixture.database.getTransfer(completedTransfer.transferId) != null)
        val localSources = evidenceDatabase.records.values.map { record ->
            (record.evidence as SourceIdentityEvidence.CompletedTransfer).source.nativeResourceId
        }.toSet()
        assertEquals(setOf("first-local-book", "second-local-book"), localSources)
    }

    @Test
    fun permanentReservationRejectionIsMappedToFailedWithoutBackoff() = runTest {
        val bytes = "rejected epub".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val transfer = uploadTransfer(contentHash, bytes.size.toLong())
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
            reservationResult = UploadReservationResult.Rejected("content_blocked"),
        )
        val fixture = uploadFixture(transfer, testImportedBook(contentHash, bytes.size.toLong()), transport)

        fixture.engine.processTransfer(transfer.transferId)

        val failed = requireNotNull(fixture.database.getTransfer(transfer.transferId))
        assertEquals("failed", failed.state)
        assertEquals("content_blocked", failed.lastError)
        assertEquals(0, failed.attemptCount)
        assertNull(failed.nextAttemptAt)
        assertEquals(0, transport.uploadCalls)
    }

    @Test
    fun localUploadBackoffUsesEveryCappedExponent() = runTest {
        val bytes = "retry epub".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 64_000L)

        expectedDelays.forEachIndexed { attemptCount, expectedDelay ->
            val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(attemptCount = attemptCount)
            val transport = UploadPathTransport(
                payload = bytes,
                file = uploadedFile(contentHash, bytes.size.toLong()),
                reserveException = IllegalStateException("network unavailable"),
            )
            val fixture = uploadFixture(transfer, testImportedBook(contentHash, bytes.size.toLong()), transport)
            val beforeRetry = Clock.System.now().toEpochMilliseconds()

            fixture.engine.processTransfer(transfer.transferId)

            val pending = requireNotNull(fixture.database.getTransfer(transfer.transferId))
            val afterRetry = Clock.System.now().toEpochMilliseconds()
            val scheduledAt = Instant.parse(requireNotNull(pending.nextAttemptAt)).toEpochMilliseconds()
            assertEquals("pending", pending.state)
            assertEquals(attemptCount + 1, pending.attemptCount)
            assertTrue(
                scheduledAt in (beforeRetry + expectedDelay)..(afterRetry + expectedDelay),
                "attempt $attemptCount expected $expectedDelay ms, got ${scheduledAt - beforeRetry} ms",
            )
        }
    }

    @Test
    fun expiredPersistedUploadSessionIsClearedThenRecreatedOnRetry() = runTest {
        val bytes = "expired epub".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            state = "transferring",
            cloudBookFileId = "old-cloud-file",
            uploadId = "old-reservation",
            storagePath = "old-storage-path",
            tusUploadUrl = "https://cloud.example/expired-session",
            tusExpiresAt = "yesterday",
            bytesTransferred = 4,
        )
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
            finalizeFailureFirst = "upload_expired",
            expireWhenResuming = true,
        )
        val fixture = uploadFixture(transfer, testImportedBook(contentHash, bytes.size.toLong()), transport)

        fixture.engine.processTransfer(transfer.transferId)

        val retryable = requireNotNull(fixture.database.getTransfer(transfer.transferId))
        assertEquals("pending", retryable.state)
        assertEquals(1, retryable.attemptCount)
        assertNull(retryable.tusUploadUrl)
        assertNull(retryable.tusExpiresAt)
        assertEquals(0L, retryable.bytesTransferred)

        fixture.engine.retry(transfer.transferId)
        fixture.database.completedTransfer.await()

        assertEquals(listOf("https://cloud.example/expired-session", null), transport.resumeUrls)
        assertEquals("completed", fixture.database.getTransfer(transfer.transferId)?.state)
        assertEquals(2, transport.reserveCalls)
        assertEquals(2, transport.uploadCalls)
    }

    @Test
    fun recoveryUsesPersistedReservationSessionAndOffsetAfterProcessRestart() = runTest {
        val bytes = "recovered upload".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            state = "transferring",
            cloudBookFileId = "persisted-cloud-file",
            uploadId = "persisted-reservation",
            storagePath = "persisted-storage-path",
            tusUploadUrl = "https://cloud.example/persisted-session",
            bytesTransferred = 5,
        )
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
            finalizeFailureFirst = "upload_incomplete",
        )
        val fixture = uploadFixture(transfer, testImportedBook(contentHash, bytes.size.toLong()), transport)

        fixture.engine.recoverPendingTransfers()
        fixture.database.completedTransfer.await()

        assertEquals(listOf<String?>("https://cloud.example/persisted-session"), transport.resumeUrls)
        assertEquals(listOf(5L), transport.resumeOffsets)
        assertEquals("completed", fixture.database.getTransfer(transfer.transferId)?.state)
    }

    @Test
    fun successfulRetryAndRestartRecordTransferEvidenceIdempotently() = runTest {
        val bytes = "retryable evidence upload".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            serverId = "parrot-cloud",
        )
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
            finalizeFailureFirst = "upload_incomplete",
            serverId = "parrot-cloud",
        )
        val evidenceDatabase = RecordingLibraryEvidenceDatabase()
        val fixture = uploadFixture(
            transfer = transfer,
            importedBook = testImportedBook(contentHash, bytes.size.toLong()),
            transport = transport,
            libraryEvidenceDatabase = evidenceDatabase,
        )

        fixture.engine.processTransfer(transfer.transferId)
        assertEquals("pending", fixture.database.getTransfer(transfer.transferId)?.state)
        fixture.engine.retry(transfer.transferId)
        fixture.database.completedTransfer.await()
        evidenceDatabase.recorded.await()
        fixture.engine.recoverPendingTransfers()
        fixture.engine.recoverPendingTransfers()

        assertEquals(1, evidenceDatabase.records.size, evidenceDatabase.records.values.toString())
        val completedEvidence = evidenceDatabase.records.values.single()
            .evidence as SourceIdentityEvidence.CompletedTransfer
        assertEquals(transfer.transferId, completedEvidence.transferId.value)
        assertEquals("local-book", completedEvidence.source.nativeResourceId)
        assertEquals("uploaded-file", completedEvidence.destination.nativeResourceId)
        assertEquals(1, evidenceDatabase.records.size)
        assertEquals(3, evidenceDatabase.recordCalls)
    }

    @Test
    fun completedUploadEvidenceUsesNormalizedLocalSnapshotIdentityImmediately() = runTest {
        val bytes = "normalized local evidence".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val importedBook = testImportedBook(contentHash, bytes.size.toLong())
        val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            serverId = "parrot-cloud",
        )
        val expectedLocalKey = SourceBookKey(
            profileId = LibraryProfileId(UserRegistry.DEFAULT_USER_ID),
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                backendId = LocalContentIdentity.BACKEND_ID,
                accountId = LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(contentHash),
        )
        val snapshotsDatabase = RecordingLibrarySourceSnapshotsDatabase(
            snapshots = listOf(
                SourceBookSnapshot(
                    source = SourceBookRef(
                        key = expectedLocalKey,
                        connectionId = SourceConnectionId(LOCAL_SERVER_ID),
                    ),
                    metadata = SourceBookMetadata(title = importedBook.title),
                    resources = listOf(
                        SourceMediaResource(
                            reference = SourceResourceRef(expectedLocalKey, importedBook.uuid),
                            mediaType = "ebook",
                            sizeBytes = importedBook.fileSize,
                            availability = SourceResourceAvailability.DevicePresent,
                            localStorageReference = DeviceStorageRef(importedBook.filePath),
                        ),
                    ),
                    status = SourceSnapshotStatus(
                        observedAt = Instant.parse("2026-01-01T00:00:00Z"),
                        presence = SourcePresence.Present,
                        isAuthoritative = true,
                    ),
                ),
            ),
        )
        val evidenceDatabase = RecordingLibraryEvidenceDatabase()
        val repositoryProvider = authenticatedRepositoryProvider()
        var cloudRepositoryAvailable = true
        val intermittentlyAvailableRepositoryProvider = object : AuthenticatedRepositoryProvider by
            repositoryProvider {
            override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? =
                if (cloudRepositoryAvailable) {
                    repositoryProvider.getBooksRepository(serverId)
                } else {
                    null
                }
        }
        val fixture = uploadFixture(
            transfer = transfer,
            importedBook = importedBook,
            transport = UploadPathTransport(
                payload = bytes,
                file = uploadedFile(contentHash, bytes.size.toLong()),
                serverId = "parrot-cloud",
            ),
            libraryEvidenceDatabase = evidenceDatabase,
            librarySourceSnapshotsDatabase = snapshotsDatabase,
            authenticatedRepositoryProvider = intermittentlyAvailableRepositoryProvider,
        )

        fixture.engine.processTransfer(transfer.transferId)
        cloudRepositoryAvailable = false
        fixture.engine.recoverPendingTransfers()
        fixture.engine.recoverPendingTransfers()

        val completed = evidenceDatabase.records.values.single()
            .evidence as SourceIdentityEvidence.CompletedTransfer
        assertEquals(expectedLocalKey, completed.source.book)
        assertEquals(importedBook.uuid, completed.source.nativeResourceId)
        assertEquals(
            SourceAccountIdentity.Portable(
                backendId = LocalContentIdentity.BACKEND_ID,
                accountId = LocalContentIdentity.ACCOUNT_ID,
            ),
            completed.source.book.accountIdentity,
        )
        assertEquals(
            SourceAccountIdentity.Portable("parrot-cloud", "cloud-account"),
            completed.destination.book.accountIdentity,
        )
    }

    @Test
    fun cancelPersistsBeforeLateUploadFinalizeAndRejectsCompletion() = runTest {
        val bytes = "cancelled late finalize".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val importedBook = testImportedBook(contentHash, bytes.size.toLong())
        val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            serverId = "parrot-cloud",
            cloudBookFileId = "reserved-file",
            uploadId = "reserved-upload",
            storagePath = "reserved-path",
            tusUploadUrl = "https://cloud.example/late-finalize",
            state = "finalizing",
            bytesTransferred = bytes.size.toLong(),
        )
        val finalizeGate = CompletableDeferred<Unit>()
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong(), "reserved-file"),
            finalizeGate = finalizeGate,
            serverId = "parrot-cloud",
        )
        val evidenceDatabase = RecordingLibraryEvidenceDatabase()
        val fileStore = FakeTransferFileStore().apply { files[importedBook.filePath] = bytes }
        val fixture = uploadFixture(
            transfer = transfer,
            importedBook = importedBook,
            transport = transport,
            fileStore = fileStore,
            libraryEvidenceDatabase = evidenceDatabase,
        )

        fixture.engine.enqueueUpload(
            serverId = transfer.serverId,
            localBookUuid = importedBook.uuid,
            rightsAttestation = UploadRightsAttestation("now", "terms", "rights"),
        )
        transport.finalizeEntered.await()
        val cancelJob = backgroundScope.launch {
            fixture.engine.cancelTransfer(transfer.transferId)
        }
        fixture.database.cancelledTransfer.await()
        finalizeGate.complete(Unit)
        cancelJob.join()

        assertNull(fixture.database.getTransfer(transfer.transferId))
        assertTrue(fixture.database.fileStates.isEmpty())
        assertTrue(evidenceDatabase.records.isEmpty())
    }

    private fun uploadFixture(
        transfer: CloudFileTransferEntity,
        importedBook: ImportedBookEntity,
        transport: UploadPathTransport,
        fileStore: FakeTransferFileStore = FakeTransferFileStore(),
        fileUsageCoordinator: LocalBookFileUsageCoordinator = LocalBookFileUsageCoordinator(),
        libraryEvidenceDatabase: LibraryEvidenceDatabase? = null,
        librarySourceSnapshotsDatabase: LibrarySourceSnapshotsDatabase? = null,
        authenticatedRepositoryProvider: AuthenticatedRepositoryProvider? = null,
    ): UploadFixture {
        val fileState = CloudBookFileEntity(
            libraryBookId = transfer.libraryBookId,
            cloudBookId = transfer.cloudBookId ?: "cloud-book",
            cloudBookFileId = transfer.cloudBookFileId ?: "pending-file",
            mediaType = transfer.mediaType,
            relativePath = "",
            fileName = "book.epub",
            status = "upload_pending",
            sizeBytes = transfer.sizeBytes,
            contentHash = transfer.contentHash.orEmpty(),
            contentHashAlgorithm = transfer.contentHashAlgorithm.orEmpty(),
            remoteRevision = 0,
            updatedAt = "before",
        )
        val database = FakeCloudFilesDatabase(transfer, fileState)
        val importedDatabase = object : ImportedBooksDatabase by UnusedImportedBooksDatabase {
            override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? =
                importedBook.takeIf { it.uuid == uuid }
        }
        val libraryBook = testLibraryBook(transfer.libraryBookId, transfer.contentHash.orEmpty())
        val libraryDatabase = object : LibraryBooksDatabase by UnusedLibraryBooksDatabase {
            override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? =
                libraryBook.takeIf { it.libraryBookId == libraryBookId }
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            importedBooksDatabase = importedDatabase,
            libraryBooksDatabase = libraryDatabase,
            transports = listOf(transport),
            fileStore = fileStore,
            fileUsageCoordinator = fileUsageCoordinator,
            libraryEvidenceDatabase = libraryEvidenceDatabase,
            librarySourceSnapshotsDatabase = librarySourceSnapshotsDatabase,
            authenticatedRepositoryProvider = authenticatedRepositoryProvider,
        )
        return UploadFixture(engine, database)
    }

    private fun authenticatedRepositoryProvider(): AuthenticatedRepositoryProvider {
        val repository = object : ServerBooksRepository {
            override val serverId = "parrot-cloud"
            override val libraryAdapterId = LibraryAdapterId("parrot-cloud")

            override suspend fun libraryAccountIdentity() =
                SourceAccountIdentity.Portable("parrot-cloud", "cloud-account")

            override fun getBooks(): Flow<AppResult<List<ServerBook>>> = emptyFlow()
            override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = emptyFlow()
            override suspend fun saveBook(book: ServerBook) = error("unused")
            override suspend fun searchBooks(query: String) = error("unused")
        }
        return object : AuthenticatedRepositoryProvider {
            override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
                flowOf(listOf(repository))

            override suspend fun getBooksRepositories(): List<ServerBooksRepository> =
                listOf(repository)

            override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? =
                repository.takeIf { candidate -> candidate.serverId == serverId }

            override suspend fun getReaderRepository(
                serverId: String,
            ): ServerReaderRepository? = null

            override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
                emptyFlow()

            override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
        }
    }

    private data class UploadFixture(
        val engine: BookFileTransferEngine,
        val database: FakeCloudFilesDatabase,
    )

    private class RecordingLibrarySourceSnapshotsDatabase(
        private val snapshots: List<SourceBookSnapshot>,
    ) : LibrarySourceSnapshotsDatabase {
        override fun observeSnapshotChanges(profileId: LibraryProfileId): Flow<Unit> = emptyFlow()

        override suspend fun saveSnapshot(snapshot: SourceBookSnapshot): LibraryGroupId =
            error("Snapshot saving is not used")

        override suspend fun getSnapshot(source: SourceBookKey): SourceBookSnapshot? =
            snapshots.singleOrNull { snapshot -> snapshot.source.key == source }

        override suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot> =
            snapshots.filter { snapshot -> snapshot.source.key.profileId == profileId }

        override suspend fun retireDeviceReplica(
            source: SourceBookRef,
            resource: SourceResourceRef,
            expectedStorageRef: DeviceStorageRef,
        ): DeviceReplicaRetirementResult = error("Replica retirement is not used")

        override suspend fun retireRemoteReplica(
            resource: SourceResourceRef,
            expectedRemoteRef: RemoteResourceRef,
        ): RemoteReplicaRetirementResult = error("Replica retirement is not used")

        override suspend fun backfillGroups(
            profileId: LibraryProfileId,
            migrationId: String,
            startedAt: String,
            completedAt: String,
        ): LibraryBackfillResult = error("Group backfill is not used")
    }

    private class RecordingLibraryEvidenceDatabase : LibraryEvidenceDatabase {
        val records = mutableMapOf<String, LibraryEvidenceRecord>()
        val recorded = CompletableDeferred<Unit>()
        var recordCalls = 0

        override suspend fun recordResource(resource: SourceResourceRef) = Unit

        override suspend fun getResources(
            book: SourceBookKey,
        ): List<SourceResourceRef> = emptyList()

        override suspend fun recordEvidence(record: LibraryEvidenceRecord): Boolean {
            recordCalls++
            val previous = records[record.evidenceId]
            if (previous != null) {
                check(previous == record) { "Evidence ID was reused for different evidence" }
                return false
            }
            records[record.evidenceId] = record
            recorded.complete(Unit)
            return true
        }

        override suspend fun getActiveEvidence(
            profileId: LibraryProfileId,
        ): List<LibraryEvidenceRecord> = records.values.filter { record ->
            val evidence = record.evidence as? SourceIdentityEvidence.CompletedTransfer
            evidence?.source?.book?.profileId == profileId
        }

        override suspend fun retireEvidence(profileId: LibraryProfileId, evidenceId: String) {
            records.remove(evidenceId)
        }
    }

    private fun uploadTransfer(contentHash: String, sizeBytes: Long) = activeTransfer().copy(
        direction = "upload",
        libraryBookId = "$CONTENT_HASH_ALGORITHM:$contentHash",
        cloudBookId = "cloud-book",
        cloudBookFileId = null,
        localSourceUuid = "local-book",
        sizeBytes = sizeBytes,
        bytesTransferred = 0,
        contentHash = contentHash,
        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
        uploadId = null,
        storagePath = null,
        tusUploadUrl = null,
        tusExpiresAt = null,
        rightsAttestation = """{"attested_at":"2026-09-24T00:00:00Z","tos_version":"test","attestation_version":"test"}""",
        state = "pending",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    private fun uploadedFile(
        contentHash: String,
        sizeBytes: Long,
        cloudBookFileId: String = "uploaded-file",
    ) = CloudBookFileRecord(
        cloudBookId = "cloud-book",
        cloudBookFileId = cloudBookFileId,
        mediaType = "EBOOK",
        relativePath = "",
        fileName = "book.epub",
        status = "available",
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
        remoteRevision = 1,
    )

    private fun testLibraryBook(libraryBookId: String, contentHash: String): LibraryBookEntity =
        object : LibraryBookEntity {
            override val libraryBookId = libraryBookId
            override val contentHash = contentHash
            override val contentHashAlgorithm = CONTENT_HASH_ALGORITHM
            override val title = "Book"
            override val author: String? = null
            override val format = "ebook"
            override val cloudBookId = "cloud-book"
        }

    private class UploadPathTransport(
        private val payload: ByteArray,
        private val file: CloudBookFileRecord,
        private val reservationResult: UploadReservationResult = UploadReservationResult.Reserved(
            UploadReservation(
                uploadId = "new-reservation",
                cloudBookFileId = file.cloudBookFileId,
                storagePath = "users/test/books/book/file.epub",
                uploadEndpoint = "https://cloud.example/upload",
            ),
        ),
        private val reserveException: Exception? = null,
        private val finalizeFailureFirst: String? = null,
        private val expireWhenResuming: Boolean = false,
        private val uploadGate: CompletableDeferred<Unit>? = null,
        private val finalizeGate: CompletableDeferred<Unit>? = null,
        override val serverId: String = SERVER_ID,
    ) : BookFileTransferTransport {
        override val capabilities = TransferTransportCapabilities(
            resumeMode = TransferResumeMode.ByteOffset,
            supportsClientSuppliedId = false,
            supportsReplaceInPlace = false,
            supportsUpload = true,
        )
        var reserveCalls = 0
        var uploadCalls = 0
        var finalizeCalls = 0
        val finalizeEntered = CompletableDeferred<Unit>()
        val resumeUrls = mutableListOf<String?>()
        val resumeOffsets = mutableListOf<Long>()

        override suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult {
            reserveCalls++
            reserveException?.let { throw it }
            return reservationResult
        }

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
            uploadCalls++
            resumeUrls += resumeUrl
            resumeOffsets += resumeOffset
            uploadGate?.await()
            if (expireWhenResuming && resumeUrl != null) {
                throw BookFileTransferSessionExpiredException()
            }
            val sessionUrl = resumeUrl ?: "https://cloud.example/session-$uploadCalls"
            onSession(sessionUrl, "tomorrow")
            onChunkHashed(payload)
            onProgress(request.sizeBytes)
            return UploadSessionResult(sessionUrl, "tomorrow", request.sizeBytes)
        }

        override suspend fun finalize(
            reservation: UploadReservation,
            request: BookFileUploadRequest,
        ): CloudBookFileRecord {
            finalizeCalls++
            if (finalizeCalls == 1 && finalizeFailureFirst != null) {
                throw BookFileTransferRejectedException(finalizeFailureFirst)
            }
            finalizeEntered.complete(Unit)
            finalizeGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return file
        }

        override suspend fun cancel(reservation: UploadReservation?, resumeUrl: String?) = Unit
    }

    private class FakeCloudFilesDatabase(
        initialTransfer: CloudFileTransferEntity? = null,
        initialFileState: CloudBookFileEntity? = null,
    ) : CloudFilesDatabase {
        private val transfers = mutableMapOf<String, CloudFileTransferEntity>().apply {
            initialTransfer?.let { put(it.transferId, it) }
        }
        val fileStates = mutableListOf<CloudBookFileEntity>().apply {
            initialFileState?.let(::add)
        }
        val insertedTransfers = mutableListOf<CloudFileTransferEntity>()
        private val pendingFileFeedChanges = mutableListOf<PendingCloudFileFeedChange>()
        private var nextPendingFileFeedChangeId = 1L
        val failedTransfer = CompletableDeferred<CloudFileTransferEntity>()
        val completedTransfer = CompletableDeferred<CloudFileTransferEntity>()
        val cancelledTransfer = CompletableDeferred<Unit>()

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
            insertedTransfers += transfer
            transfers[transfer.transferId] = transfer
        }

        override suspend fun getTransfer(transferId: String): CloudFileTransferEntity? = transfers[transferId]

        override suspend fun updateTransfer(transfer: CloudFileTransferEntity) {
            transfers[transfer.transferId] = transfer
            if (transfer.state == "failed") failedTransfer.complete(transfer)
            if (transfer.state == "completed") completedTransfer.complete(transfer)
            if (transfer.state == "cancelled") cancelledTransfer.complete(Unit)
        }

        override suspend fun updateTransferIfState(
            transfer: CloudFileTransferEntity,
            expectedStates: List<String>,
        ): Boolean {
            val current = transfers[transfer.transferId] ?: return false
            if (current.state !in expectedStates) return false
            updateTransfer(transfer)
            return true
        }

        override suspend fun deleteTransfer(transferId: String) {
            transfers.remove(transferId)
        }

        override suspend fun getTransfers(serverId: String, states: List<String>): List<CloudFileTransferEntity> =
            transfers.values.filter { it.serverId == serverId && it.state in states }

        override suspend fun getTransfersForCloudFile(
            cloudBookFileId: String,
        ): List<CloudFileTransferEntity> =
            transfers.values.filter { it.cloudBookFileId == cloudBookFileId }

        override suspend fun enqueuePendingFileFeedChange(
            cloudBookId: String,
            feedRevision: Long?,
            payloadJson: String,
            receivedAt: String,
        ): PendingCloudFileFeedChange {
            val existing = pendingFileFeedChanges.firstOrNull { change ->
                change.cloudBookId == cloudBookId && change.payloadJson == payloadJson
            }
            if (existing != null) return existing
            return PendingCloudFileFeedChange(
                id = nextPendingFileFeedChangeId++,
                cloudBookId = cloudBookId,
                feedRevision = feedRevision,
                payloadJson = payloadJson,
                receivedAt = receivedAt,
            ).also(pendingFileFeedChanges::add)
        }

        override suspend fun getPendingFileFeedChanges(
            cloudBookId: String,
        ): List<PendingCloudFileFeedChange> = pendingFileFeedChanges
            .filter { change -> change.cloudBookId == cloudBookId }
            .sortedWith(
                compareBy<PendingCloudFileFeedChange> { change -> change.feedRevision == null }
                    .thenBy { change -> change.feedRevision }
                    .thenBy { change -> change.id },
            )

        override suspend fun getPendingFileFeedCloudBookIds(): List<String> =
            pendingFileFeedChanges.map { change -> change.cloudBookId }.distinct().sorted()

        override suspend fun deletePendingFileFeedChange(changeId: Long) {
            pendingFileFeedChanges.removeAll { change -> change.id == changeId }
        }

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

    private class ResumingDownloadTransport(
        override val serverId: String = SERVER_ID,
    ) : BookFileDownloadTransport {
        override val supportsDownload: Boolean = true
        var requestedOffset: Long? = null
        var requestedFileId: String? = null

        override suspend fun download(
            request: BookFileDownloadRequest,
            resumeOffset: Long,
            onResponseOffset: suspend (offset: Long) -> Unit,
            onChunk: suspend (bytes: ByteArray) -> Unit,
        ) {
            requestedFileId = request.cloudBookFileId
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
        val failedDeletes = mutableSetOf<String>()
        var onContentHash: (String) -> Unit = {}
        override fun stagingPath(transferId: String) = "/staging/$transferId.part"
        override fun importedFilePath(localUuid: String, mediaType: String) = "/imports/$localUuid.epub"
        override suspend fun exists(path: String) = path in files
        override suspend fun size(path: String) = files[path]?.size?.toLong() ?: 0L
        override fun contentHash(path: String): String {
            onContentHash(path)
            return sha256(files[path] ?: error("No file at $path")).toHexString()
        }
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
        override suspend fun delete(path: String): Boolean {
            if (path in failedDeletes) return false
            files.remove(path)
            return true
        }
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

    private fun testImportedBook(
        contentHash: String = "hash",
        fileSize: Long = 512,
        uuid: String = "local-book",
        filePath: String = "/imports/local-book.epub",
        origin: String = "import",
    ): ImportedBookEntity = object : ImportedBookEntity {
        override val uuid = uuid
        override val title = "Book"
        override val author: String? = null
        override val description: String? = null
        override val coverPath: String? = null
        override val filePath = filePath
        override val fileSize = fileSize
        override val contentHash: String? = contentHash
        override val contentHashAlgorithm: String? = "sha-256-v1"
        override val importedAt = "before"
        override val lastOpenedAt: String? = null
        override val bookType = "EBOOK"
        override val publicationDate: String? = null
        override val origin = origin
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
