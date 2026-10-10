package com.retro99.books.data.transfer

import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.InMemoryCloudFilesDatabase
import com.retro99.books.data.InMemoryFileStore
import com.retro99.books.data.FakeDeviceFilesDatabase
import com.retro99.books.data.FakeLibraryBooksDatabase
import com.retro99.books.data.Sha256Digest
import com.retro99.books.data.sha256
import com.retro99.books.data.testDeviceFile
import com.retro99.books.data.testLibraryBook
import com.retro99.books.data.toHexString
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.BookFileTransferTransport
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A prepared chapter archive goes up through the book file transfer engine's own
 * reserve, resumable upload and finalize path and its persisted queue. There is
 * no second uploader, so these tests drive the real engine with a fake transport
 * and assert the chapter reaches the server as one more file of its book.
 */
class PreparedAudioUploadTest {

    @Test
    fun aPreparedChapterGoesUpThroughTheSameReserveUploadFinalizePath() = runTest {
        val fixture = fixture()

        val transferId = fixture.enqueue()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("completed", transfer.state)
        // Its own slot, its own bytes: both are what the engine gained.
        assertEquals(PREPARED_PATH, transfer.relativePath)
        assertEquals(ARCHIVE_PATH, transfer.sourcePath)
        assertEquals(listOf(PREPARED_PATH), fixture.transport.reservedPaths)
        assertEquals(listOf(MEDIA_TYPE), fixture.transport.reservedMediaTypes)
        assertEquals(1, fixture.transport.finalizeCount)
    }

    @Test
    fun theUploadedBytesAreTheArchiveNotTheBook() = runTest {
        val fixture = fixture()

        fixture.enqueue()

        assertEquals(ARCHIVE.decodeToString(), fixture.transport.uploaded.decodeToString())
        assertEquals(ARCHIVE_PATH, fixture.transport.uploadedFrom)
    }

    @Test
    fun theChapterBecomesOneMoreAvailableFileOfItsBook() = runTest {
        val fixture = fixture()

        fixture.enqueue()

        val states = fixture.files.getFileStates(LIBRARY_BOOK_ID)
        val chapter = assertNotNull(states.firstOrNull { it.relativePath == PREPARED_PATH })
        assertEquals("available", chapter.status)
        assertEquals(MEDIA_TYPE, chapter.mediaType)
        assertEquals(ARCHIVE.size.toLong(), chapter.sizeBytes)
        // The book's own row is untouched beside it.
        val book = assertNotNull(states.firstOrNull { it.relativePath.isEmpty() })
        assertEquals("ebook", book.mediaType)
        assertEquals("available", book.status)
    }

    @Test
    fun preparingTheSameChapterWithTheSameSettingsDoesNotUploadTwice() = runTest {
        val fixture = fixture()

        val first = fixture.enqueue()
        val second = fixture.enqueue()

        assertEquals(first, second)
        assertEquals(1, fixture.transport.reservedPaths.size)
        assertEquals(1, fixture.files.transfers.value.size)
    }

    @Test
    fun preparingItWithOtherSettingsIsADifferentFile() = runTest {
        val fixture = fixture()
        val otherPath = "tts-prepared/${"a".repeat(64)}/${"c".repeat(64)}.zip"
        fixture.store.files[OTHER_ARCHIVE_PATH] = ARCHIVE

        val first = fixture.enqueue()
        val second = fixture.enqueue(
            relativePath = otherPath,
            sourcePath = OTHER_ARCHIVE_PATH,
        )

        assertTrue(first != second)
        assertEquals(listOf(PREPARED_PATH, otherPath), fixture.transport.reservedPaths)
        assertEquals(2, fixture.files.getFileStates(LIBRARY_BOOK_ID).count { it.relativePath.isNotEmpty() })
    }

    @Test
    fun aChapterOfAnotherBookIsItsOwnTransfer() = runTest {
        val fixture = fixture()

        fixture.enqueue()
        val second = fixture.enqueue(libraryBookId = OTHER_BOOK_ID)

        assertEquals("completed", fixture.files.getTransfer(second)?.state)
        assertEquals(2, fixture.files.transfers.value.size)
    }

    // -----------------------------------------------------------------------
    // The states
    // -----------------------------------------------------------------------

    @Test
    fun storageFullIsPermanentWithNoRetryScheduled() = runTest {
        val fixture = fixture(
            transport = PreparedAudioTransport(
                reserveResult = UploadReservationResult.Rejected(
                    reason = "quota_exceeded",
                    usedBytes = 209_715_200,
                    quotaBytes = 209_715_200,
                ),
            ),
        )

        val transferId = fixture.enqueue()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("failed", transfer.state)
        assertEquals("quota_exceeded", transfer.lastError)
        // No retry loop: one message, and nothing scheduled to try again.
        assertNull(transfer.nextAttemptAt)
    }

    @Test
    fun anAccountThatMayNotUploadFailsWithoutRetrying() = runTest {
        val fixture = fixture(
            transport = PreparedAudioTransport(
                reserveResult = UploadReservationResult.Rejected(reason = "uploads_not_enabled"),
            ),
        )

        val transferId = fixture.enqueue()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("failed", transfer.state)
        assertEquals("uploads_not_enabled", transfer.lastError)
        assertNull(transfer.nextAttemptAt)
    }

    @Test
    fun aBookWithNoBackupIsRefusedByTheServerAndNotRetried() = runTest {
        val fixture = fixture(
            transport = PreparedAudioTransport(
                reserveResult = UploadReservationResult.Rejected(reason = "book_backup_unavailable"),
            ),
        )

        val transferId = fixture.enqueue()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("failed", transfer.state)
        assertEquals("book_backup_unavailable", transfer.lastError)
    }

    @Test
    fun aTemporaryFailureIsRetriedRatherThanAbandoned() = runTest {
        val fixture = fixture(
            transport = PreparedAudioTransport(
                reserveResult = UploadReservationResult.Rejected(
                    reason = "too_many_pending_uploads",
                    retryAfterMillis = 60_000,
                ),
            ),
        )

        val transferId = fixture.enqueueOnce()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("pending", transfer.state)
        assertNotNull(transfer.nextAttemptAt)
        assertEquals(1, transfer.attemptCount)
    }

    @Test
    fun aChapterAlreadyInTheCloudIsNotUploadedAgain() = runTest {
        val fixture = fixture(
            transport = PreparedAudioTransport(
                reserveResult = UploadReservationResult.AlreadyAvailable(uploadedRecord()),
            ),
        )

        val transferId = fixture.enqueue()

        assertEquals("completed", fixture.files.getTransfer(transferId)?.state)
        assertEquals(0, fixture.transport.uploaded.size)
    }

    // -----------------------------------------------------------------------
    // Deleting the chapter
    // -----------------------------------------------------------------------

    @Test
    fun deletingTheChapterCancelsAPendingUploadAndLeavesNoTransfer() = runTest {
        val fixture = fixture(
            transport = PreparedAudioTransport(
                reserveResult = UploadReservationResult.Rejected(
                    reason = "too_many_pending_uploads",
                    retryAfterMillis = 60_000,
                ),
            ),
        )
        val transferId = fixture.enqueueOnce()
        assertEquals("pending", fixture.files.getTransfer(transferId)?.state)

        fixture.engine.cancelUpload(SERVER_ID, LIBRARY_BOOK_ID, MEDIA_TYPE, PREPARED_PATH)

        assertNull(fixture.files.getTransfer(transferId))
    }

    @Test
    fun deletingTheChapterRemovesOnlyItsOwnCloudFile() = runTest {
        val fixture = fixture()
        fixture.enqueue()

        fixture.engine.deleteRemoteFile(SERVER_ID, LIBRARY_BOOK_ID, MEDIA_TYPE, PREPARED_PATH)

        assertEquals(listOf(uploadedRecord().cloudBookFileId), fixture.deletions.deleted)
        val states = fixture.files.getFileStates(LIBRARY_BOOK_ID)
        assertTrue(states.none { it.relativePath == PREPARED_PATH })
        assertTrue(states.any { it.relativePath.isEmpty() }, "the book's own backup stays")
    }

    @Test
    fun deletingTheBookBackupStillTargetsTheBookNotAChapter() = runTest {
        val fixture = fixture()
        fixture.enqueue()

        fixture.engine.deleteRemoteBackup(SERVER_ID, LIBRARY_BOOK_ID, "ebook")

        assertEquals(listOf("book-file"), fixture.deletions.deleted)
        assertTrue(
            fixture.files.getFileStates(LIBRARY_BOOK_ID).any { it.relativePath == PREPARED_PATH },
            "the chapter row is the server's to cascade, not the client's to guess",
        )
    }

    @Test
    fun aCallerThatDoesNotKnowTheHashSchemeNeedNotSupplyOne() = runTest {
        val fixture = fixture()

        val transferId = fixture.engine.enqueueAuxiliaryUpload(
            serverId = SERVER_ID,
            libraryBookId = LIBRARY_BOOK_ID,
            mediaType = MEDIA_TYPE,
            relativePath = PREPARED_PATH,
            sourcePath = ARCHIVE_PATH,
            sizeBytes = ARCHIVE.size.toLong(),
            rightsAttestation = ATTESTATION,
        )
        fixture.scheduler.advanceUntilIdle()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("completed", transfer.state)
        // The engine hashed the file in its own scheme, so the reader side
        // never has to know what that scheme is.
        assertEquals(fixture.store.contentHash(ARCHIVE_PATH), transfer.contentHash)
        assertEquals(CONTENT_HASH_ALGORITHM, transfer.contentHashAlgorithm)
    }

    // -----------------------------------------------------------------------
    // The facts the queueing decision reads
    // -----------------------------------------------------------------------

    @Test
    fun theCloudFilesOfABookAreReadableWithoutTouchingTheDatabase() = runTest {
        val fixture = fixture()
        fixture.enqueue()

        val files = fixture.engine.cloudFilesFor(LIBRARY_BOOK_ID)

        val book = assertNotNull(files.firstOrNull { it.relativePath.isEmpty() })
        assertEquals("ebook", book.mediaType)
        assertEquals("available", book.status)
        val chapter = assertNotNull(files.firstOrNull { it.relativePath == PREPARED_PATH })
        assertEquals(MEDIA_TYPE, chapter.mediaType)
        assertEquals("available", chapter.status)
        assertEquals(ARCHIVE.size.toLong(), chapter.sizeBytes)
    }

    @Test
    fun aChaptersOwnTransferIsFoundByItsRelativePath() = runTest {
        val fixture = fixture()
        val otherPath = "tts-prepared/${"a".repeat(64)}/${"c".repeat(64)}.zip"
        fixture.store.files[OTHER_ARCHIVE_PATH] = ARCHIVE
        fixture.enqueue()
        fixture.enqueue(relativePath = otherPath, sourcePath = OTHER_ARCHIVE_PATH)

        val transfers = fixture.engine.transfersFor(SERVER_ID, LIBRARY_BOOK_ID)

        // Without the relative path the two chapters are indistinguishable.
        assertEquals(
            setOf(PREPARED_PATH, otherPath),
            transfers.filter { it.mediaType == MEDIA_TYPE }.map { it.relativePath }.toSet(),
        )
        val mine = assertNotNull(transfers.firstOrNull { it.relativePath == PREPARED_PATH })
        assertEquals("completed", mine.state)
        assertEquals("upload", mine.direction)
    }

    @Test
    fun aTransferWaitingForAnotherAttemptSaysSo() = runTest {
        val fixture = fixture(
            transport = PreparedAudioTransport(
                reserveResult = UploadReservationResult.Rejected(
                    reason = "too_many_pending_uploads",
                    retryAfterMillis = 60_000,
                ),
            ),
        )
        fixture.enqueueOnce()

        val transfer = assertNotNull(
            fixture.engine.transfersFor(SERVER_ID, LIBRARY_BOOK_ID)
                .firstOrNull { it.relativePath == PREPARED_PATH },
        )

        assertEquals("pending", transfer.state)
        assertTrue(transfer.willRetry, "a scheduled next attempt is what willRetry means")
    }

    @Test
    fun booksGoFirstSoTheirPendingUploadsAreCounted() = runTest {
        val fixture = fixture()

        assertEquals(0, fixture.engine.pendingBookUploadCount(SERVER_ID))

        // A book's own upload, left waiting to retry.
        fixture.files.insertTransfer(
            pendingUpload(transferId = "book-upload", mediaType = "ebook", relativePath = ""),
        )
        // The chapter's own upload must not count itself as a book.
        fixture.files.insertTransfer(
            pendingUpload(
                transferId = "chapter-upload",
                mediaType = MEDIA_TYPE,
                relativePath = PREPARED_PATH,
            ),
        )

        assertEquals(1, fixture.engine.pendingBookUploadCount(SERVER_ID))
    }

    // -----------------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------------

    @Test
    fun anEmptyRelativePathIsRefusedSoAnOlderAppCannotMisreadIt() = runTest {
        val fixture = fixture()

        val error = runCatching { fixture.enqueue(relativePath = "") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException, "got $error")
        assertTrue(fixture.files.transfers.value.isEmpty())
    }

    @Test
    fun anArchiveThatDoesNotMatchItsDeclaredSizeIsRefused() = runTest {
        val fixture = fixture()

        val error = runCatching { fixture.enqueue(sizeBytes = ARCHIVE.size + 1L) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException, "got $error")
        assertTrue(fixture.files.transfers.value.isEmpty())
    }

    @Test
    fun aBookWhoseMetadataHasNotSyncedYetIsRefused() = runTest {
        val fixture = fixture(remoteRevision = null)

        val error = runCatching { fixture.enqueue() }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "got $error")
        assertTrue(fixture.files.transfers.value.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private fun TestScope.fixture(
        transport: PreparedAudioTransport = PreparedAudioTransport(),
        remoteRevision: Long? = 1,
    ): Fixture = fixture(
        queueContext = StandardTestDispatcher(testScheduler),
        transport = transport,
        remoteRevision = remoteRevision,
    )

    private fun fixture(
        queueContext: CoroutineContext,
        transport: PreparedAudioTransport = PreparedAudioTransport(),
        remoteRevision: Long? = 1,
    ): Fixture {
        val files = InMemoryCloudFilesDatabase(
            CloudBookFileEntity(
                libraryBookId = LIBRARY_BOOK_ID,
                cloudBookFileId = "book-file",
                mediaType = "ebook",
                relativePath = "",
                fileName = "book.epub",
                status = "available",
                sizeBytes = 1_000,
                contentHash = "b".repeat(64),
                contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
                remoteRevision = 1,
                updatedAt = "before",
            ),
        )
        val store = InMemoryFileStore().also { it.files[ARCHIVE_PATH] = ARCHIVE }
        val deletions = RecordingDeletions()
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = files,
            deviceFilesDatabase = FakeDeviceFilesDatabase(testDeviceFile(LIBRARY_BOOK_ID)),
            libraryBooksDatabase = FakeLibraryBooksDatabase(
                testLibraryBook(LIBRARY_BOOK_ID, remoteRevision = remoteRevision),
                testLibraryBook(OTHER_BOOK_ID, remoteRevision = remoteRevision),
            ),
            transports = listOf(transport),
            deletionTransports = listOf(deletions),
            fileStore = store,
            queueContext = queueContext,
        )
        return Fixture((queueContext as TestDispatcher).scheduler, engine, files, store, transport, deletions)
    }

    private class Fixture(
        val scheduler: TestCoroutineScheduler,
        val engine: BookFileTransferEngine,
        val files: InMemoryCloudFilesDatabase,
        val store: InMemoryFileStore,
        val transport: PreparedAudioTransport,
        val deletions: RecordingDeletions,
    ) {
        /**
         * Enqueuing already schedules the work on the engine's own queue, which
         * is the point -- there is no second uploader to drive. So this waits
         * for that queue to settle rather than processing the transfer by hand.
         */
        /**
         * Stops after the first attempt, before any retry timer fires, so a
         * test can see the state the user is shown while a retry is waiting.
         */
        suspend fun enqueueOnce(): String = enqueue(settle = false)

        suspend fun enqueue(
            libraryBookId: String = LIBRARY_BOOK_ID,
            relativePath: String = PREPARED_PATH,
            sourcePath: String = ARCHIVE_PATH,
            sizeBytes: Long = ARCHIVE.size.toLong(),
            settle: Boolean = true,
        ): String {
            val transferId = engine.enqueueAuxiliaryUpload(
                serverId = SERVER_ID,
                libraryBookId = libraryBookId,
                mediaType = MEDIA_TYPE,
                relativePath = relativePath,
                sourcePath = sourcePath,
                sizeBytes = sizeBytes,
                contentHash = ARCHIVE_HASH,
                contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
                rightsAttestation = ATTESTATION,
            )
            if (settle) scheduler.advanceUntilIdle() else scheduler.runCurrent()
            return transferId
        }

        private fun CloudFileTransferEntity?.isSettled(): Boolean {
            val transfer = this ?: return true
            return transfer.state == "completed" ||
                transfer.state == "failed" ||
                transfer.state == "cancelled" ||
                (transfer.state == "pending" && transfer.nextAttemptAt != null)
        }
    }

    private class RecordingDeletions : com.retro99.books.domain.BookFileDeletionTransport {
        val deleted = mutableListOf<String>()
        override val serverId = SERVER_ID
        override suspend fun delete(cloudBookFileId: String) {
            deleted += cloudBookFileId
        }
    }

    /** Records what the engine asked of the server, and streams the real bytes. */
    private class PreparedAudioTransport(
        private val reserveResult: UploadReservationResult = UploadReservationResult.Reserved(
            UploadReservation(
                uploadId = "reservation-1",
                cloudBookFileId = "chapter-file",
                storagePath = "users/u/books/b/chapter",
                uploadEndpoint = "/storage/v1/upload/resumable",
            ),
        ),
    ) : BookFileTransferTransport {
        val reservedPaths = mutableListOf<String>()
        val reservedMediaTypes = mutableListOf<String>()
        var uploaded = ByteArray(0)
        var uploadedFrom: String? = null
        var finalizeCount = 0

        override val serverId = SERVER_ID
        override val capabilities = TransferTransportCapabilities(
            resumeMode = TransferResumeMode.ByteOffset,
            supportsClientSuppliedId = true,
            supportsReplaceInPlace = true,
            supportsUpload = true,
        )

        override suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult {
            reservedPaths += request.relativePath
            reservedMediaTypes += request.mediaType
            return reserveResult
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
            uploadedFrom = request.localPath
            uploaded = ARCHIVE
            onSession("https://cloud.example/session", null)
            onChunkHashed(ARCHIVE)
            onProgress(ARCHIVE.size.toLong())
            return UploadSessionResult(
                uploadUrl = "https://cloud.example/session",
                expiresAt = null,
                bytesTransferred = ARCHIVE.size.toLong(),
            )
        }

        override suspend fun finalize(
            reservation: UploadReservation,
            request: BookFileUploadRequest,
        ): CloudBookFileRecord {
            finalizeCount++
            return uploadedRecord(request.relativePath)
        }

        override suspend fun cancel(reservation: UploadReservation?, resumeUrl: String?) = Unit
    }

    private companion object {
        const val SERVER_ID = "parrot-cloud"
        const val MEDIA_TYPE = "tts_prepared_audio"
        const val LIBRARY_BOOK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_BOOK_ID = "22222222-2222-4222-8222-222222222222"
        const val ARCHIVE_PATH = "/data/tts-prepared/chapter.zip"
        const val OTHER_ARCHIVE_PATH = "/data/tts-prepared/chapter-other.zip"
        val PREPARED_PATH = "tts-prepared/${"a".repeat(64)}/${"b".repeat(64)}.zip"
        val ARCHIVE = "PK stored prepared chapter archive".encodeToByteArray()
        val ARCHIVE_HASH = sha256(ARCHIVE).toHexString()
        val ATTESTATION = UploadRightsAttestation(
            attestedAt = "2026-10-10T00:00:00Z",
            tosVersion = "test",
            attestationVersion = "test",
        )

        fun pendingUpload(
            transferId: String,
            mediaType: String,
            relativePath: String,
        ) = CloudFileTransferEntity(
            transferId = transferId,
            serverId = SERVER_ID,
            direction = "upload",
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookFileId = null,
            mediaType = mediaType,
            stagingPath = null,
            sizeBytes = 10,
            bytesTransferred = 0,
            contentHash = null,
            contentHashAlgorithm = null,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            tusExpiresAt = null,
            rightsAttestation = null,
            relativePath = relativePath,
            sourcePath = null,
            state = "pending",
            attemptCount = 0,
            nextAttemptAt = null,
            lastError = null,
            createdAt = "a",
            updatedAt = "a",
        )

        fun uploadedRecord(relativePath: String = PREPARED_PATH) = CloudBookFileRecord(
            cloudBookFileId = "chapter-file",
            mediaType = MEDIA_TYPE,
            relativePath = relativePath,
            fileName = "chapter.zip",
            status = "available",
            sizeBytes = ARCHIVE.size.toLong(),
            contentHash = ARCHIVE_HASH,
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            remoteRevision = 1,
        )
    }
}
