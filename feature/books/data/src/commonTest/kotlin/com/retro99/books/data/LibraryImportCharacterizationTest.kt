package com.retro99.books.data

import com.github.michaelbull.result.get
import com.retro99.books.data.model.LibraryBookJsonCodec
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what importing a staged file into the library does today. Written before the
 * staged-import extraction; these tests must keep passing unchanged.
 */
class LibraryImportCharacterizationTest {

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooks = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val failingLibraryBooks = FailingInsertLibraryBooksDatabase(libraryBooks, failInserts = false)
    private val cloudFiles = InMemoryCloudFilesDatabase()
    private val fileStore = InMemoryFileStore()
    private val classUnderTest = LibraryLocalDataSource(
        libraryBooksDatabase = failingLibraryBooks,
        deviceFilesDatabase = deviceFiles,
        cloudFilesDatabase = cloudFiles,
        databaseExecutor = DirectDatabaseExecutor,
        fileStore = fileStore,
    )

    @Test
    fun `a new file becomes one book with a random UUID and one imported device file`() = runTest {
        // Given
        val bytes = "new book bytes".encodeToByteArray()
        val cover = byteArrayOf(1, 2, 3)
        val file = stage(
            bytes = bytes,
            metadata = testEpubMetadata(title = "Dune", coverBytes = cover, isbn = "9780441013593"),
        )

        // When
        val bookId = requireNotNull(classUnderTest.addImportedFile(file).get())

        // Then
        assertTrue(UUID_V4_PATTERN.matches(bookId), bookId)
        val book = libraryBooks.books.value.values.single()
        assertEquals(bookId, book.libraryBookId)
        assertEquals("Dune", book.title)
        assertEquals("Author", book.author)
        assertEquals("Description", book.description)
        assertEquals("2001-02-03", book.publicationDate)
        assertEquals("""{"isbn":"9780441013593"}""", book.metadataJson)
        assertEquals(file.contentHash, book.sourceContentHash)
        assertEquals(CONTENT_HASH_ALGORITHM, book.sourceContentHashAlgorithm)
        assertNull(book.remoteRevision)
        assertNull(book.lastOpenedAt)
        assertNull(book.deletedAt)

        val deviceFile = deviceFiles.files.value.single()
        assertEquals(bookId, deviceFile.libraryBookId)
        assertEquals("ebook", deviceFile.mediaType)
        assertEquals("import", deviceFile.origin)
        assertEquals(file.contentHash, deviceFile.contentHash)
        assertEquals(CONTENT_HASH_ALGORITHM, deviceFile.contentHashAlgorithm)
        assertEquals(bytes.size.toLong(), deviceFile.fileSize)
        assertEquals(fileStore.libraryFilePath(bookId, "ebook"), deviceFile.filePath)

        assertEquals("/covers/$bookId.png", book.coverPath)
        assertContentEquals(cover, fileStore.files[book.coverPath])
    }

    @Test
    fun `the staged file is moved into the library store instead of copied`() = runTest {
        // Given
        val bytes = "moved bytes".encodeToByteArray()
        val file = stage(bytes)

        // When
        val bookId = requireNotNull(classUnderTest.addImportedFile(file).get())

        // Then
        assertEquals(setOf("/library/${bookId}_ebook.epub"), fileStore.files.keys)
        assertContentEquals(bytes, fileStore.files["/library/${bookId}_ebook.epub"])
    }

    @Test
    fun `a book without a cover in its file has no cover path`() = runTest {
        // Given
        val file = stage("no cover".encodeToByteArray(), metadata = testEpubMetadata(coverBytes = null))

        // When
        val bookId = requireNotNull(classUnderTest.addImportedFile(file).get())

        // Then
        assertNull(libraryBooks.getLibraryBookById(bookId)?.coverPath)
        assertTrue(fileStore.files.keys.none { path -> path.startsWith("/covers/") })
    }

    @Test
    fun `two different files get different random ids`() = runTest {
        // When
        val first = classUnderTest.addImportedFile(stage("one".encodeToByteArray())).get()
        val second = classUnderTest.addImportedFile(stage("two".encodeToByteArray())).get()

        // Then
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun `the same bytes imported again return the first book and leave everything as it was`() = runTest {
        // Given
        val bytes = "same bytes".encodeToByteArray()
        val firstId = requireNotNull(classUnderTest.addImportedFile(stage(bytes)).get())
        val bookBefore = libraryBooks.getLibraryBookById(firstId)
        val fileBefore = deviceFiles.files.value.single()
        val again = stage(bytes, stagedPath = "/staging/again.tmp.epub")

        // When
        val secondId = classUnderTest.addImportedFile(again).get()

        // Then
        assertEquals(firstId, secondId)
        assertEquals(bookBefore, libraryBooks.books.value.values.single())
        assertEquals(fileBefore, deviceFiles.files.value.single())
        assertEquals(1, libraryBooks.outbox.size)
        assertEquals(setOf(fileBefore.filePath), fileStore.files.keys)
        assertContentEquals(bytes, fileStore.files[fileBefore.filePath])
    }

    @Test
    fun `different bytes with the same title and isbn become a separate book`() = runTest {
        // Given
        val metadata = testEpubMetadata(title = "Dune", isbn = "9780441013593")
        val firstBytes = "first edition".encodeToByteArray()
        val secondBytes = "second edition".encodeToByteArray()
        val firstId = requireNotNull(classUnderTest.addImportedFile(stage(firstBytes, metadata)).get())
        val firstPath = deviceFiles.files.value.single().filePath

        // When
        val secondId = requireNotNull(classUnderTest.addImportedFile(stage(secondBytes, metadata)).get())

        // Then
        assertNotEquals(firstId, secondId)
        assertEquals(setOf(firstId, secondId), libraryBooks.books.value.keys)
        assertEquals(2, deviceFiles.files.value.size)
        val secondPath = deviceFiles.files.value.single { file -> file.libraryBookId == secondId }.filePath
        assertNotEquals(firstPath, secondPath)
        assertContentEquals(firstBytes, fileStore.files[firstPath])
        assertContentEquals(secondBytes, fileStore.files[secondPath])
        assertEquals(listOf(firstId, secondId), libraryBooks.outbox.map(SyncOutboxEntry::entityId))
    }

    @Test
    fun `the media type chosen from media overlays is stored as given`() = runTest {
        // Given
        val file = stage(
            bytes = "read-aloud".encodeToByteArray(),
            metadata = testEpubMetadata(hasMediaOverlays = true),
            mediaType = "readaloud",
        )

        // When
        val bookId = requireNotNull(classUnderTest.addImportedFile(file).get())

        // Then
        val deviceFile = deviceFiles.files.value.single()
        assertEquals("readaloud", deviceFile.mediaType)
        assertEquals("/library/${bookId}_readaloud.epub", deviceFile.filePath)
        assertTrue(""""format":"readaloud"""" in libraryBooks.outbox.single().payload)
    }

    @Test
    fun `a new book writes exactly one library book upsert to the outbox`() = runTest {
        // Given
        val file = stage("outbox".encodeToByteArray(), testEpubMetadata(isbn = "9780441013593"))

        // When
        val bookId = requireNotNull(classUnderTest.addImportedFile(file).get())

        // Then
        val book = assertNotNull(libraryBooks.getLibraryBookById(bookId))
        val entry = libraryBooks.outbox.single()
        assertEquals("library_book", entry.entityType)
        assertEquals(bookId, entry.entityId)
        assertEquals("upsert", entry.operation)
        assertEquals(LibraryBookJsonCodec.encode(book, format = "ebook"), entry.payload)
        assertNull(entry.baseRevision)
        assertNull(entry.cloudUserId)
        assertEquals("pending", entry.state)
        assertEquals(0, entry.attemptCount)
    }

    @Test
    fun `attaching a file to a book that is already known writes nothing to the outbox`() = runTest {
        // Given
        val bytes = "known book".encodeToByteArray()
        val hash = sha256(bytes).toHexString()
        libraryBooks.upsertLibraryBook(testLibraryBook(KNOWN_BOOK_ID, sourceContentHash = hash))

        // When
        val bookId = classUnderTest.addImportedFile(stage(bytes)).get()

        // Then
        assertEquals(KNOWN_BOOK_ID, bookId)
        assertEquals("import", deviceFiles.files.value.single().origin)
        assertTrue(libraryBooks.outbox.isEmpty())
    }

    @Test
    fun `a failed database write leaves no rows and no library file or cover`() = runTest {
        // Given
        failingLibraryBooks.failInserts = true
        val file = stage("will fail".encodeToByteArray(), testEpubMetadata(coverBytes = byteArrayOf(9)))

        // When
        val result = classUnderTest.addImportedFile(file)

        // Then
        assertNull(result.get())
        assertTrue(libraryBooks.books.value.isEmpty())
        assertTrue(deviceFiles.files.value.isEmpty())
        assertTrue(libraryBooks.outbox.isEmpty())
        assertTrue(fileStore.files.keys.none { path -> path.startsWith("/library/") })
        assertTrue(fileStore.files.keys.none { path -> path.startsWith("/covers/") })
    }

    private fun stage(
        bytes: ByteArray,
        metadata: EpubMetadata = testEpubMetadata(),
        mediaType: String = "ebook",
        stagedPath: String = "/staging/${sha256(bytes).toHexString()}.tmp.epub",
    ): ImportedFileCandidate {
        fileStore.files[stagedPath] = bytes
        return ImportedFileCandidate(
            stagedPath = stagedPath,
            mediaType = mediaType,
            fileSize = bytes.size.toLong(),
            contentHash = sha256(bytes).toHexString(),
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            metadata = metadata,
        )
    }

    private companion object {
        const val KNOWN_BOOK_ID = "99999999-9999-4999-8999-999999999999"
        val UUID_V4_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
