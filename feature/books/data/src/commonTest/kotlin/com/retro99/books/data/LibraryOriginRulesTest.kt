package com.retro99.books.data

import com.github.michaelbull.result.get
import com.retro99.books.data.source.AddedLibraryFile
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.books.domain.BookFileProvenance
import com.retro99.database.api.library.DeviceFileEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the device-file origin decides: metadata sync and which copies count as imports. */
class LibraryOriginRulesTest {

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooks = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val fileStore = InMemoryFileStore()
    private val classUnderTest = LibraryLocalDataSource(
        libraryBooksDatabase = libraryBooks,
        deviceFilesDatabase = deviceFiles,
        cloudFilesDatabase = InMemoryCloudFilesDatabase(),
        databaseExecutor = DirectDatabaseExecutor,
        fileStore = fileStore,
    )

    @Test
    fun `a file picked by you writes its book to the metadata outbox`() = runTest {
        // When
        val added = assertNotNull(
            classUnderTest.addStagedFile(stage("picked", DeviceFileEntity.ORIGIN_IMPORT)).get(),
        )

        // Then
        assertEquals("import", deviceFiles.files.value.single().origin)
        assertEquals(listOf(added.libraryBookId), libraryBooks.outbox.map { entry -> entry.entityId })
    }

    @Test
    fun `a catalogue download becomes a book on this device and writes nothing to the outbox`() = runTest {
        // Given
        val file = stage("catalogue", DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD)
            .copy(provenance = BookFileProvenance(sourceId = "source-1", publicationKey = "urn:book:1"))

        // When
        val added = assertNotNull(classUnderTest.addStagedFile(file).get())

        // Then
        assertTrue(added.isNewBook)
        val book = assertNotNull(libraryBooks.getLibraryBookById(added.libraryBookId))
        assertEquals("catalogue", book.title)
        assertNull(book.remoteRevision)
        val deviceFile = deviceFiles.files.value.single()
        assertEquals("catalogue_download", deviceFile.origin)
        assertEquals(file.contentHash, deviceFile.contentHash)
        assertTrue(deviceFile.filePath in fileStore.files)
        assertTrue(libraryBooks.outbox.isEmpty())
    }

    @Test
    fun `a catalogue download of bytes you already imported keeps the import as it is`() = runTest {
        // Given
        val importedId = assertNotNull(
            classUnderTest.addStagedFile(stage("same", DeviceFileEntity.ORIGIN_IMPORT)).get(),
        ).libraryBookId
        val importedFile = deviceFiles.files.value.single()

        // When
        val matched = classUnderTest.addStagedFile(
            stage("same", DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD, stagedPath = "/staging/again.epub"),
        ).get()

        // Then
        assertEquals(AddedLibraryFile(importedId, isNewBook = false), matched)
        assertEquals(listOf(importedFile), deviceFiles.files.value)
        assertEquals(1, libraryBooks.outbox.size)
    }

    @Test
    fun `picking bytes you already downloaded from a catalogue does not start syncing the book`() = runTest {
        // Given
        val downloadedId = assertNotNull(
            classUnderTest.addStagedFile(stage("same", DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD)).get(),
        ).libraryBookId

        // When
        val matched = classUnderTest.addStagedFile(
            stage("same", DeviceFileEntity.ORIGIN_IMPORT, stagedPath = "/staging/again.epub"),
        ).get()

        // Then
        assertEquals(AddedLibraryFile(downloadedId, isNewBook = false), matched)
        assertEquals("catalogue_download", deviceFiles.files.value.single().origin)
        assertTrue(libraryBooks.outbox.isEmpty())
    }

    @Test
    fun `keeping device files as imports converts cloud downloads only`() = runTest {
        // Given
        deviceFiles.upsertDeviceFile(
            testDeviceFile(BOOK_ID, mediaType = "ebook", origin = DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD),
        )
        deviceFiles.upsertDeviceFile(
            testDeviceFile(BOOK_ID, mediaType = "readaloud", origin = DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD),
        )
        deviceFiles.upsertDeviceFile(
            testDeviceFile(OTHER_BOOK_ID, origin = DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD),
        )

        // When
        classUnderTest.keepDeviceFilesAsImports(BOOK_ID)

        // Then
        assertEquals("import", deviceFiles.getDeviceFile(BOOK_ID, "ebook")?.origin)
        assertEquals("catalogue_download", deviceFiles.getDeviceFile(BOOK_ID, "readaloud")?.origin)
        assertEquals("cloud_download", deviceFiles.getDeviceFile(OTHER_BOOK_ID, "ebook")?.origin)
    }

    private fun stage(
        content: String,
        origin: String,
        stagedPath: String = "/staging/$content.epub",
    ): ImportedFileCandidate {
        val bytes = content.encodeToByteArray()
        fileStore.files[stagedPath] = bytes
        return ImportedFileCandidate(
            stagedPath = stagedPath,
            mediaType = "ebook",
            fileSize = bytes.size.toLong(),
            contentHash = sha256(bytes).toHexString(),
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            metadata = testEpubMetadata(title = content),
            origin = origin,
        )
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_BOOK_ID = "22222222-2222-4222-8222-222222222222"
    }
}
