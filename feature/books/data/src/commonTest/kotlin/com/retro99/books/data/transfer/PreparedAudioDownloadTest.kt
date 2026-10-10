package com.retro99.books.data.transfer

import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.InMemoryCloudFilesDatabase
import com.retro99.books.data.InMemoryFileStore
import com.retro99.books.data.FakeDeviceFilesDatabase
import com.retro99.books.data.FakeLibraryBooksDatabase
import com.retro99.books.data.sha256
import com.retro99.books.data.testDeviceFile
import com.retro99.books.data.testLibraryBook
import com.retro99.books.data.toHexString
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.PREPARED_AUDIO_MEDIA_TYPE
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step 4's half of the engine: a prepared chapter archive comes down through
 * the same resumable, retrying, hash-verifying download path a book takes, and
 * is then left alone. It is not a device copy of a book and must never be
 * installed as one; what to do with the bytes belongs to the reader.
 */
class PreparedAudioDownloadTest {

    @Test
    fun theArchiveComesDownToThePathTheCallerNamed() = runTest {
        val fixture = fixture()

        val transferId = fixture.download()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("completed", transfer.state)
        assertEquals("download", transfer.direction)
        assertEquals(PREPARED_AUDIO_MEDIA_TYPE, transfer.mediaType)
        assertEquals(PREPARED_PATH, transfer.relativePath)
        assertEquals(ARCHIVE.decodeToString(), fixture.store.files[INBOX_PATH]?.decodeToString())
        assertEquals(ARCHIVE.size.toLong(), transfer.bytesTransferred)
    }

    @Test
    fun itIsNotInstalledAsADeviceCopyOfTheBook() = runTest {
        val fixture = fixture()

        fixture.download()

        // The book's own device file is untouched and no new one appeared.
        assertEquals(1, fixture.deviceFiles.files.value.size)
        assertEquals("ebook", fixture.deviceFiles.files.value.single().mediaType)
        assertNull(fixture.deviceFiles.getDeviceFile(LIBRARY_BOOK_ID, PREPARED_AUDIO_MEDIA_TYPE))
    }

    @Test
    fun theStagingCopyIsGoneAndTheTransferKeepsNoStagingPath() = runTest {
        val fixture = fixture()

        val transferId = fixture.download()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertNull(transfer.stagingPath)
        assertTrue(
            fixture.store.files.keys.none { path -> path != INBOX_PATH && path.contains("staging") },
            "only the named destination is left: ${fixture.store.files.keys}",
        )
    }

    @Test
    fun anArchiveWhoseBytesDoNotMatchTheCloudHashIsFailedAndDeleted() = runTest {
        val fixture = fixture(transport = PreparedAudioDownloads(bytes = TAMPERED))

        val transferId = fixture.download()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("failed", transfer.state)
        assertEquals("verify_failed", transfer.lastError)
        assertFalse(INBOX_PATH in fixture.store.files, "a rejected archive is never left in place")
    }

    @Test
    fun aFileGoneFromTheCloudIsRefusedBeforeAnyTransferRowExists() = runTest {
        val fixture = fixture(cloudChapter = null)

        val error = runCatching { fixture.download() }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "got $error")
        assertTrue(fixture.files.transfers.value.isEmpty())
    }

    @Test
    fun aFileThatGoesFromTheCloudBetweenAttemptsEndsFailedWithTheServersReason() = runTest {
        // The first attempt fails for a network reason, so a retry is waiting.
        val fixture = fixture(transport = PreparedAudioDownloads(failFirstAttempt = true))
        // Stops after the first attempt, before the retry timer fires.
        val transferId = fixture.download(settle = false)
        assertEquals("pending", assertNotNull(fixture.files.getTransfer(transferId)).state)

        // Meanwhile the row goes, as a takedown, a deleted book backup or the
        // server's own cascade would make it go.
        fixture.files.fileStates.value = emptyList()
        fixture.engine.retry(transferId)
        fixture.scheduler.advanceUntilIdle()

        val transfer = assertNotNull(fixture.files.getTransfer(transferId))
        assertEquals("failed", transfer.state)
        assertEquals("cloud_file_unavailable", transfer.lastError)
        assertFalse(INBOX_PATH in fixture.store.files)
    }

    @Test
    fun askingTwiceDownloadsOnce() = runTest {
        val fixture = fixture()

        val first = fixture.download()
        val second = fixture.download()

        assertEquals(first, second)
        assertEquals(1, fixture.transport.downloadCount)
    }

    @Test
    fun aBookFileDownloadStillGoesThroughTheFinalizer() = runTest {
        val fixture = fixture()

        fixture.engine.enqueueDownload(SERVER_ID, LIBRARY_BOOK_ID, "ebook")
        fixture.scheduler.advanceUntilIdle()

        assertEquals(
            1,
            fixture.finalizer.finalized.size,
            "the book's own restore path is untouched by the auxiliary branch",
        )
        assertEquals("ebook", fixture.finalizer.finalized.single().mediaType)
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private fun TestScope.fixture(
        transport: PreparedAudioDownloads = PreparedAudioDownloads(),
        cloudChapter: CloudBookFileEntity? = chapterFile(),
    ): Fixture {
        val queueContext = StandardTestDispatcher(testScheduler)
        val files = InMemoryCloudFilesDatabase(
            *listOfNotNull(bookFile(), cloudChapter).toTypedArray(),
        )
        val store = InMemoryFileStore()
        val deviceFiles = FakeDeviceFilesDatabase(testDeviceFile(LIBRARY_BOOK_ID))
        val finalizer = RecordingFinalizer(files)
        val engine = BookFileTransferEngine(
            cloudFilesDatabase = files,
            deviceFilesDatabase = deviceFiles,
            libraryBooksDatabase = FakeLibraryBooksDatabase(testLibraryBook(LIBRARY_BOOK_ID, remoteRevision = 1)),
            transports = emptyList(),
            downloadTransports = listOf(transport),
            downloadFinalizer = finalizer,
            fileStore = store,
            queueContext = queueContext,
        )
        return Fixture(queueContext.scheduler, engine, files, store, deviceFiles, transport, finalizer)
    }

    private class Fixture(
        val scheduler: TestCoroutineScheduler,
        val engine: BookFileTransferEngine,
        val files: InMemoryCloudFilesDatabase,
        val store: InMemoryFileStore,
        val deviceFiles: FakeDeviceFilesDatabase,
        val transport: PreparedAudioDownloads,
        val finalizer: RecordingFinalizer,
    ) {
        suspend fun download(settle: Boolean = true): String {
            val transferId = engine.enqueueAuxiliaryDownload(
                serverId = SERVER_ID,
                libraryBookId = LIBRARY_BOOK_ID,
                mediaType = PREPARED_AUDIO_MEDIA_TYPE,
                relativePath = PREPARED_PATH,
                destinationPath = INBOX_PATH,
            )
            if (settle) scheduler.advanceUntilIdle() else scheduler.runCurrent()
            return transferId
        }
    }

    /** Streams the archive, and counts how often it was asked to. */
    private class PreparedAudioDownloads(
        private val bytes: ByteArray = ARCHIVE,
        private val failFirstAttempt: Boolean = false,
    ) : BookFileDownloadTransport {
        var downloadCount = 0
        override val serverId = SERVER_ID
        override val supportsDownload = true

        override suspend fun download(
            request: BookFileDownloadRequest,
            resumeOffset: Long,
            onResponseOffset: suspend (offset: Long) -> Unit,
            onChunk: suspend (bytes: ByteArray) -> Unit,
        ) {
            downloadCount++
            if (failFirstAttempt && downloadCount == 1) error("no network")
            onResponseOffset(0)
            onChunk(bytes)
        }
    }

    /** Stands in for the real book finalizer, and records what reached it. */
    private class RecordingFinalizer(
        private val files: InMemoryCloudFilesDatabase,
    ) : DownloadTransferFinalizer {
        val finalized = mutableListOf<BookFileDownloadRequest>()

        override suspend fun finalize(
            transfer: com.retro99.database.api.cloudfiles.CloudFileTransferEntity,
            request: BookFileDownloadRequest,
        ): com.retro99.database.api.cloudfiles.CloudFileTransferEntity {
            finalized += request
            val completed = transfer.copy(
                state = "completed",
                stagingPath = null,
                bytesTransferred = request.sizeBytes,
            )
            files.updateTransfer(completed)
            return completed
        }
    }

    private companion object {
        const val SERVER_ID = "parrot-cloud"
        const val LIBRARY_BOOK_ID = "11111111-1111-4111-8111-111111111111"
        const val INBOX_PATH = "/data/tts-prepared-inbox/chapter.zip"
        val PREPARED_PATH = "tts-prepared/${"a".repeat(64)}/${"b".repeat(64)}.zip"
        val ARCHIVE = "PK stored prepared chapter archive".encodeToByteArray()
        /** The same length, so what is caught is the hash and not the size. */
        val TAMPERED = "PK stored prepared chapter ARCHIVE".encodeToByteArray()
        val ARCHIVE_HASH = sha256(ARCHIVE).toHexString()

        fun bookFile() = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookFileId = "book-file",
            mediaType = "ebook",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = ARCHIVE.size.toLong(),
            contentHash = ARCHIVE_HASH,
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            remoteRevision = 1,
            updatedAt = "before",
        )

        fun chapterFile() = CloudBookFileEntity(
            libraryBookId = LIBRARY_BOOK_ID,
            cloudBookFileId = "chapter-file",
            mediaType = PREPARED_AUDIO_MEDIA_TYPE,
            relativePath = PREPARED_PATH,
            fileName = "${"b".repeat(64)}.zip",
            status = "available",
            sizeBytes = ARCHIVE.size.toLong(),
            contentHash = ARCHIVE_HASH,
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            remoteRevision = 1,
            updatedAt = "before",
        )
    }
}
