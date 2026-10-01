package com.retro99.books.data.transfer

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.EpubMetadata
import com.retro99.books.data.EpubMetadataExtractor
import com.retro99.books.data.FakeDeviceFilesDatabase
import com.retro99.books.data.FakeLibraryBooksDatabase
import com.retro99.books.data.InMemoryCloudFilesDatabase
import com.retro99.books.data.InMemoryFileStore
import com.retro99.books.data.sha256
import com.retro99.books.data.testDeviceFile
import com.retro99.books.data.testLibraryBook
import com.retro99.books.data.toHexString
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFinalizerTest {

    @Test
    fun `a finalized download becomes a cloud download copy of the same book`() = runTest {
        // Given
        val bytes = "complete restored book".encodeToByteArray()
        val fixture = fixture(bytes, testLibraryBook(BOOK_ID, coverPath = "/c.png", description = "d"))

        // When
        val completed = fixture.finalizer.finalize(fixture.transfer, fixture.request)

        // Then
        val file = fixture.deviceFiles.files.value.single()
        assertEquals(BOOK_ID, file.libraryBookId)
        assertEquals("ebook", file.mediaType)
        assertEquals(DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD, file.origin)
        assertEquals("/library/${BOOK_ID}_ebook.epub", file.filePath)
        assertTrue(file.filePath in fixture.fileStore.files)
        assertTrue(STAGING_PATH !in fixture.fileStore.files)
        assertEquals("completed", completed.state)
        assertEquals("completed", fixture.cloudFiles.getTransfer("download-1")?.state)
        assertEquals(0, fixture.metadataExtractor.calls)
    }

    @Test
    fun `a download for a book that has not arrived by pull creates the book`() = runTest {
        // Given
        val bytes = "book without a row yet".encodeToByteArray()
        val fixture = fixture(bytes, libraryBook = null)

        // When
        fixture.finalizer.finalize(fixture.transfer, fixture.request)

        // Then
        val book = assertNotNull(fixture.libraryBooks.getLibraryBookById(BOOK_ID))
        assertEquals("Restored title", book.title)
        assertEquals("/covers/$BOOK_ID.png", book.coverPath)
        assertEquals(sha256(bytes).toHexString(), book.sourceContentHash)
    }

    @Test
    fun `a pulled book without a cover gets the cover from the file`() = runTest {
        // Given
        val bytes = "book with cover".encodeToByteArray()
        val fixture = fixture(bytes, testLibraryBook(BOOK_ID))

        // When
        fixture.finalizer.finalize(fixture.transfer, fixture.request)

        // Then
        val book = assertNotNull(fixture.libraryBooks.getLibraryBookById(BOOK_ID))
        assertEquals("Book", book.title)
        assertEquals("/covers/$BOOK_ID.png", book.coverPath)
        assertEquals("Restored description", book.description)
    }

    @Test
    fun `a verified device copy is kept instead of copied again`() = runTest {
        // Given
        val bytes = "already on device".encodeToByteArray()
        val existing = testDeviceFile(
            libraryBookId = BOOK_ID,
            filePath = "/library/existing.epub",
            contentHash = sha256(bytes).toHexString(),
            fileSize = bytes.size.toLong(),
        )
        val fixture = fixture(
            bytes,
            testLibraryBook(BOOK_ID, coverPath = "/c.png", description = "d"),
            existingFile = existing,
        )
        fixture.fileStore.files[existing.filePath] = bytes

        // When
        fixture.finalizer.finalize(fixture.transfer, fixture.request)

        // Then
        assertEquals(listOf(existing), fixture.deviceFiles.files.value)
        assertTrue("/library/${BOOK_ID}_ebook.epub" !in fixture.fileStore.files)
        assertTrue(STAGING_PATH !in fixture.fileStore.files)
    }

    @Test
    fun `a missing or truncated staging file is not published`() = runTest {
        // Given
        val bytes = "full file".encodeToByteArray()
        val fixture = fixture(bytes, testLibraryBook(BOOK_ID), seedStaging = false)
        fixture.fileStore.files[STAGING_PATH] = "part".encodeToByteArray()

        // When
        assertFailsWith<IllegalStateException> {
            fixture.finalizer.finalize(fixture.transfer, fixture.request)
        }

        // Then
        assertTrue(fixture.deviceFiles.files.value.isEmpty())
        assertNull(fixture.cloudFiles.getTransfer("download-1"))
        assertEquals(0, fixture.metadataExtractor.calls)
    }

    @Test
    fun `a file that does not match the cloud hash is rejected`() = runTest {
        // Given
        val bytes = "expected".encodeToByteArray()
        val fixture = fixture(bytes, testLibraryBook(BOOK_ID))
        fixture.fileStore.files[STAGING_PATH] = "tampered".encodeToByteArray()
            .copyOf(bytes.size)

        // When
        assertFailsWith<DownloadHashMismatchException> {
            fixture.finalizer.finalize(fixture.transfer, fixture.request)
        }

        // Then
        assertTrue(fixture.deviceFiles.files.value.isEmpty())
    }

    private fun fixture(
        bytes: ByteArray,
        libraryBook: LibraryBookEntity?,
        existingFile: DeviceFileEntity? = null,
        seedStaging: Boolean = true,
    ): Fixture {
        val fileStore = InMemoryFileStore().also { store ->
            if (seedStaging) store.files[STAGING_PATH] = bytes
        }
        val deviceFiles = existingFile?.let { file -> FakeDeviceFilesDatabase(file) }
            ?: FakeDeviceFilesDatabase()
        val libraryBooks = libraryBook?.let { book -> FakeLibraryBooksDatabase(book) }
            ?: FakeLibraryBooksDatabase()
        val cloudFiles = InMemoryCloudFilesDatabase()
        val metadataExtractor = RecordingMetadataExtractor()
        val hash = sha256(bytes).toHexString()
        return Fixture(
            finalizer = DownloadFinalizer(
                libraryBooksDatabase = libraryBooks,
                deviceFilesDatabase = deviceFiles,
                cloudFilesDatabase = cloudFiles,
                metadataExtractor = metadataExtractor,
                fileStore = fileStore,
            ),
            libraryBooks = libraryBooks,
            deviceFiles = deviceFiles,
            cloudFiles = cloudFiles,
            fileStore = fileStore,
            metadataExtractor = metadataExtractor,
            transfer = downloadTransfer(bytes.size.toLong(), hash),
            request = request(hash, bytes.size.toLong()),
        )
    }

    private data class Fixture(
        val finalizer: DownloadFinalizer,
        val libraryBooks: FakeLibraryBooksDatabase,
        val deviceFiles: FakeDeviceFilesDatabase,
        val cloudFiles: InMemoryCloudFilesDatabase,
        val fileStore: InMemoryFileStore,
        val metadataExtractor: RecordingMetadataExtractor,
        val transfer: CloudFileTransferEntity,
        val request: BookFileDownloadRequest,
    )

    private class RecordingMetadataExtractor : EpubMetadataExtractor {
        var calls = 0

        override suspend fun extractMetadata(filePath: String): AppResult<EpubMetadata> {
            calls++
            return Ok(
                EpubMetadata(
                    title = "Restored title",
                    author = "Restored author",
                    description = "Restored description",
                    coverBytes = byteArrayOf(1, 2, 3),
                    hasMediaOverlays = false,
                    publicationDate = null,
                ),
            )
        }
    }

    private fun downloadTransfer(sizeBytes: Long, hash: String) = CloudFileTransferEntity(
        transferId = "download-1",
        serverId = "parrot-cloud",
        direction = "download",
        libraryBookId = BOOK_ID,
        cloudBookFileId = "cloud-book-file",
        mediaType = "ebook",
        stagingPath = STAGING_PATH,
        sizeBytes = sizeBytes,
        bytesTransferred = sizeBytes,
        contentHash = hash,
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
        libraryBookId = BOOK_ID,
        cloudBookFileId = "cloud-book-file",
        mediaType = "ebook",
        fileName = "restored.epub",
        sizeBytes = sizeBytes,
        contentHash = hash,
        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
    )

    private companion object {
        const val BOOK_ID = "88888888-8888-4888-8888-888888888888"
        const val STAGING_PATH = "/staging/download-1.part"
    }
}
