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
import com.retro99.books.domain.TransferResumeMode
import com.retro99.books.domain.TransferTransportCapabilities
import com.retro99.books.domain.UploadReservation
import com.retro99.books.domain.UploadReservationResult
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.books.domain.UploadSessionResult
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.FakeDeviceFilesDatabase
import com.retro99.books.data.FakeLibraryBooksDatabase
import com.retro99.books.data.InMemoryFileStore
import com.retro99.books.data.testDeviceFile
import com.retro99.books.data.testLibraryBook
import com.retro99.books.data.sha256
import com.retro99.books.data.toHexString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.retro99.sync.domain.SyncPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class BookFileTransferEngineTest {
    @Test
    fun downloadResumesFromDurablePartAndFinalizesReplica() = runTest {
        val state = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
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
            stagingPath = "/staging/transfer.part",
            sizeBytes = 6,
            bytesTransferred = 2,
            contentHash = "expected-hash",
            contentHashAlgorithm = "sha-256-v1",
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        ), state)
        val fileStore = InMemoryFileStore().apply { files["/staging/transfer.part"] = "ab".encodeToByteArray() }
        val transport = ResumingDownloadTransport()
        val finalized = CompletableDeferred<Unit>()
        val finalizer = object : DownloadTransferFinalizer {
            override suspend fun finalize(
                transfer: CloudFileTransferEntity,
                request: BookFileDownloadRequest,
            ): CloudFileTransferEntity {
                assertEquals("abcdef", fileStore.files.getValue("/staging/transfer.part").decodeToString())
                val completed = transfer.copy(state = "completed", stagingPath = null, bytesTransferred = 6)
                database.updateTransfer(completed)
                finalized.complete(Unit)
                return completed
            }
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = FakeDeviceFilesDatabase(),
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
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
    fun `remove download deletes only that media type's device copy`() = runTest {
        // Given
        val ebook = testDeviceFile(LIBRARY_BOOK_ID, mediaType = "ebook")
        val readAloud = testDeviceFile(LIBRARY_BOOK_ID, mediaType = "readaloud")
        val deviceFiles = FakeDeviceFilesDatabase(ebook, readAloud)
        val fileStore = InMemoryFileStore().apply {
            files[ebook.filePath] = byteArrayOf(1)
            files[readAloud.filePath] = byteArrayOf(2)
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = FakeCloudFilesDatabase(),
            deviceFilesDatabase = deviceFiles,
            libraryBooksDatabase = FakeLibraryBooksDatabase(testLibraryBook(LIBRARY_BOOK_ID)),
            transports = emptyList(),
            fileStore = fileStore,
        )

        // When
        engine.removeDownload(SERVER_ID, LIBRARY_BOOK_ID, "ebook")

        // Then
        assertEquals(listOf(readAloud), deviceFiles.files.value)
        assertTrue(ebook.filePath !in fileStore.files)
        assertTrue(readAloud.filePath in fileStore.files)
    }

    @Test
    fun cancelPersistsLocalTerminalStateBeforeBestEffortRemoteCleanup() = runTest {
        val transfer = activeTransfer()
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
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
            deviceFilesDatabase = FakeDeviceFilesDatabase(),
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
            transports = listOf(transport),
        )

        engine.cancel(SERVER_ID, LIBRARY_BOOK_ID)

        assertNull(filesDatabase.getTransfer(transfer.transferId))
        assertNull(filesDatabase.fileStates.singleOrNull())
        assertEquals("reservation-1", transport.cancelledReservationId)
    }

    @Test
    fun deleteRemoteBackupRemovesItsRestoredReplicaAndLocalTransferState() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "completed",
            stagingPath = null,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
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
        val fileStore = InMemoryFileStore().apply {
            files["/library/restored.epub"] = "restored".encodeToByteArray()
        }
        val deviceFiles = FakeDeviceFilesDatabase(
            testDeviceFile(
                libraryBookId = LIBRARY_BOOK_ID,
                filePath = "/library/restored.epub",
                origin = DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD,
            ),
        )
        val deletionTransport = RecordingDeletionTransport()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = deviceFiles,
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
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
        assertTrue("/library/restored.epub" !in fileStore.files)
        assertTrue(deviceFiles.files.value.isEmpty())
    }

    @Test
    fun deleteRemoteBackupCanClearAnUploadFailedFileState() = runTest {
        val transfer = activeTransfer().copy(
            state = "failed",
            lastError = "file_exists",
        )
        val fileState = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
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
            deviceFilesDatabase = FakeDeviceFilesDatabase(),
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
            transports = emptyList(),
            deletionTransports = listOf(deletionTransport),
            fileStore = InMemoryFileStore(),
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
        val fileStore = InMemoryFileStore().apply {
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
            deviceFilesDatabase = FakeDeviceFilesDatabase(),
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
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
            deviceFilesDatabase = FakeDeviceFilesDatabase(),
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
            transports = emptyList(),
            downloadTransports = listOf(FailingDownloadTransport()),
            downloadFinalizer = object : DownloadTransferFinalizer {
                override suspend fun finalize(
                    transfer: CloudFileTransferEntity,
                    request: BookFileDownloadRequest,
                ): CloudFileTransferEntity = error("Finalization is not expected after a failed download")
            },
            fileStore = InMemoryFileStore(),
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
            deviceFilesDatabase = FakeDeviceFilesDatabase(),
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
            transports = emptyList(),
            downloadTransports = listOf(FailingDownloadTransport()),
            downloadFinalizer = object : DownloadTransferFinalizer {
                override suspend fun finalize(
                    transfer: CloudFileTransferEntity,
                    request: BookFileDownloadRequest,
                ): CloudFileTransferEntity = error("Finalization is not expected after a failed download")
            },
            fileStore = InMemoryFileStore(),
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
        val deviceFiles = FakeDeviceFilesDatabase(testDeviceFile(LIBRARY_BOOK_ID, mediaType = "EBOOK"))
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = deviceFiles,
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
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
        val deviceFiles = FakeDeviceFilesDatabase(testDeviceFile(LIBRARY_BOOK_ID, mediaType = "EBOOK"))
        val transport = HashMismatchedUploadTransport()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = deviceFiles,
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
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
            stagingPath = null,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        )
        val database = FakeCloudFilesDatabase(transfer, CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
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
        val original = testDeviceFile(LIBRARY_BOOK_ID, filePath = "/library/original.epub")
        val deviceFiles = FakeDeviceFilesDatabase(original)
        val fileStore = InMemoryFileStore().apply {
            files[original.filePath] = "original".encodeToByteArray()
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = deviceFiles,
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
            transports = emptyList(),
            fileStore = fileStore,
        )

        engine.invalidateCloudFile("cloud-file")

        assertTrue(original.filePath in fileStore.files)
        assertEquals(listOf(original), deviceFiles.files.value)
        assertNull(database.getTransfer(TRANSFER_ID))
    }

    @Test
    fun invalidatingCloudFileDeletesOnlyItsCloudDownloadedReplica() = runTest {
        val transfer = activeTransfer().copy(
            direction = "download",
            state = "completed",
            stagingPath = null,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
        )
        val database = FakeCloudFilesDatabase(transfer, CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
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
        val restored = testDeviceFile(
            libraryBookId = LIBRARY_BOOK_ID,
            filePath = "/library/restored.epub",
            fileSize = 8,
            origin = DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD,
        )
        val deviceFiles = FakeDeviceFilesDatabase(restored)
        val fileStore = InMemoryFileStore().apply {
            files[restored.filePath] = "bookdata".encodeToByteArray()
        }
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = deviceFiles,
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
            transports = emptyList(),
            fileStore = fileStore,
        )

        engine.invalidateCloudFile("cloud-file")

        assertTrue(deviceFiles.files.value.isEmpty())
        assertTrue(restored.filePath !in fileStore.files)
        assertNull(database.getTransfer(TRANSFER_ID))
    }

    @Test
    fun uploadHappyPathCommitsAvailableFileAfterHashVerification() = runTest {
        val bytes = "uploaded epub bytes".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val transfer = uploadTransfer(contentHash, bytes.size.toLong())
        val transport = UploadPathTransport(bytes, uploadedFile(contentHash, bytes.size.toLong()))
        val fixture = uploadFixture(transfer, uploadSource(contentHash, bytes.size.toLong()), transport)

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
        val importedBook = uploadSource(contentHash, bytes.size.toLong())
        val transfer = uploadTransfer(contentHash, bytes.size.toLong()).copy(
            attemptCount = 2,
            nextAttemptAt = "2999-01-01T00:00:00Z",
            lastError = "network unavailable",
        )
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
        )
        val fileStore = InMemoryFileStore().apply { files[importedBook.filePath] = bytes }
        val fixture = uploadFixture(transfer, importedBook, transport, fileStore)

        assertEquals(transfer.transferId, fixture.engine.enqueueUpload(SERVER_ID, importedBook.libraryBookId, importedBook.mediaType,
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
    fun concurrentEnqueueUploadCallsCreateOneTransferRow() = runTest {
        val bytes = "double tap source".encodeToByteArray()
        val contentHash = sha256(bytes).toHexString()
        val importedBook = uploadSource(contentHash, bytes.size.toLong())
        val database = FakeCloudFilesDatabase()
        val fileStore = InMemoryFileStore().apply { files[importedBook.filePath] = bytes }
        val transport = UploadPathTransport(
            payload = bytes,
            file = uploadedFile(contentHash, bytes.size.toLong()),
        )
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = FakeDeviceFilesDatabase(importedBook),
            libraryBooksDatabase = FakeLibraryBooksDatabase(testLibraryBook(LIBRARY_BOOK_ID)),
            transports = listOf(transport),
            fileStore = fileStore,
        )

        val ids = coroutineScope {
            listOf(
                async { engine.enqueueUpload(SERVER_ID, importedBook.libraryBookId, importedBook.mediaType, UploadRightsAttestation("now", "terms", "rights")) },
                async { engine.enqueueUpload(SERVER_ID, importedBook.libraryBookId, importedBook.mediaType, UploadRightsAttestation("now", "terms", "rights")) },
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
        val uploadedBook = uploadSource(
            uploadHash,
            uploadBytes.size.toLong(),
            filePath = "/library/imported.epub",
        )
        val cloudReplica = uploadSource(
            uploadHash,
            uploadBytes.size.toLong(),
            libraryBookId = RESTORED_BOOK_ID,
            filePath = "/library/restored.epub",
            origin = DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD,
        )
        val unlinkedImport = uploadSource(
            missingHash,
            missingBytes.size.toLong(),
            libraryBookId = UNSYNCED_BOOK_ID,
            filePath = "/library/unsynced.epub",
        )
        val deviceFiles = FakeDeviceFilesDatabase(uploadedBook, cloudReplica, unlinkedImport)
        // The unsynced book has no remote revision yet, so it can't be uploaded.
        val libraryDatabase = FakeLibraryBooksDatabase(
            testLibraryBook(LIBRARY_BOOK_ID),
            testLibraryBook(RESTORED_BOOK_ID),
            testLibraryBook(UNSYNCED_BOOK_ID, remoteRevision = null),
        )
        val fileStore = InMemoryFileStore().apply {
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
            deviceFilesDatabase = deviceFiles,
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
        assertEquals(
            listOf(LIBRARY_BOOK_ID),
            database.insertedTransfers.map { transfer -> transfer.libraryBookId },
        )
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
            deviceFilesDatabase = FakeDeviceFilesDatabase(),
            libraryBooksDatabase = FakeLibraryBooksDatabase(),
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
        val fixture = uploadFixture(transfer, uploadSource(contentHash, bytes.size.toLong()), transport)

        fixture.engine.processTransfer(transfer.transferId)

        assertEquals("completed", fixture.database.getTransfer(transfer.transferId)?.state)
        assertEquals(1, transport.reserveCalls)
        assertEquals(0, transport.uploadCalls)
        assertEquals(0, transport.finalizeCalls)
        assertEquals("deduped-file", fixture.database.fileStates.single().cloudBookFileId)
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
        val fixture = uploadFixture(transfer, uploadSource(contentHash, bytes.size.toLong()), transport)

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
            val fixture = uploadFixture(transfer, uploadSource(contentHash, bytes.size.toLong()), transport)
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
        val fixture = uploadFixture(transfer, uploadSource(contentHash, bytes.size.toLong()), transport)

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
        val fixture = uploadFixture(transfer, uploadSource(contentHash, bytes.size.toLong()), transport)

        fixture.engine.recoverPendingTransfers()
        fixture.database.completedTransfer.await()

        assertEquals(listOf<String?>("https://cloud.example/persisted-session"), transport.resumeUrls)
        assertEquals(listOf(5L), transport.resumeOffsets)
        assertEquals("completed", fixture.database.getTransfer(transfer.transferId)?.state)
    }

    private fun uploadFixture(
        transfer: CloudFileTransferEntity,
        source: DeviceFileEntity,
        transport: UploadPathTransport,
        fileStore: InMemoryFileStore = InMemoryFileStore(),
    ): UploadFixture {
        val fileState = CloudBookFileEntity(
            libraryBookId = transfer.libraryBookId,
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
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = database,
            deviceFilesDatabase = FakeDeviceFilesDatabase(source),
            libraryBooksDatabase = FakeLibraryBooksDatabase(testLibraryBook(transfer.libraryBookId)),
            transports = listOf(transport),
            fileStore = fileStore,
        )
        return UploadFixture(engine, database)
    }

    private fun uploadSource(
        contentHash: String,
        sizeBytes: Long,
        libraryBookId: String = LIBRARY_BOOK_ID,
        filePath: String = "/library/book.epub",
        origin: String = DeviceFileEntity.ORIGIN_IMPORT,
    ) = testDeviceFile(
        libraryBookId = libraryBookId,
        mediaType = "EBOOK",
        filePath = filePath,
        contentHash = contentHash,
        fileSize = sizeBytes,
        origin = origin,
    )

    private data class UploadFixture(
        val engine: BookFileTransferEngine,
        val database: FakeCloudFilesDatabase,
    )

    private fun uploadTransfer(contentHash: String, sizeBytes: Long) = activeTransfer().copy(
        direction = "upload",
        libraryBookId = LIBRARY_BOOK_ID,
        cloudBookFileId = null,
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
    ) : BookFileTransferTransport {
        override val serverId = SERVER_ID
        override val capabilities = TransferTransportCapabilities(
            resumeMode = TransferResumeMode.ByteOffset,
            supportsClientSuppliedId = false,
            supportsReplaceInPlace = false,
            supportsUpload = true,
        )
        var reserveCalls = 0
        var uploadCalls = 0
        var finalizeCalls = 0
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
        val failedTransfer = CompletableDeferred<CloudFileTransferEntity>()
        val completedTransfer = CompletableDeferred<CloudFileTransferEntity>()

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

        override suspend fun getFileStateById(cloudBookFileId: String): CloudBookFileEntity? =
            fileStates.firstOrNull { it.cloudBookFileId == cloudBookFileId }

        override suspend fun findFileStateByHash(algorithm: String, hash: String): CloudBookFileEntity? =
            fileStates.firstOrNull { it.contentHashAlgorithm == algorithm && it.contentHash == hash }

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

    private companion object {
        const val SERVER_ID = "test-cloud"
        const val LIBRARY_BOOK_ID = "55555555-5555-4555-8555-555555555555"
        const val RESTORED_BOOK_ID = "66666666-6666-4666-8666-666666666666"
        const val UNSYNCED_BOOK_ID = "77777777-7777-4777-8777-777777777777"
        const val TRANSFER_ID = "transfer-1"

        fun activeTransfer() = CloudFileTransferEntity(
            transferId = TRANSFER_ID,
            serverId = SERVER_ID,
            direction = "upload",
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookFileId = "cloud-file",
            mediaType = "EBOOK",
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
