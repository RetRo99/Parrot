package com.retro99.books.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryImportTest {

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooks = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val cloudFiles = InMemoryCloudFilesDatabase()
    private val fileStore = InMemoryFileStore()
    private val classUnderTest = LibraryLocalDataSource(
        libraryBooksDatabase = libraryBooks,
        deviceFilesDatabase = deviceFiles,
        cloudFilesDatabase = cloudFiles,
        databaseExecutor = DirectDatabaseExecutor,
        fileStore = fileStore,
        importJournal = FakeLibraryImportJournal(),
    )

    @Test
    fun `a new file creates a new UUID book and one outbox entry`() = runTest {
        // Given
        val file = stage("new book", hash = "hash-a")

        // When
        val bookId = requireNotNull(classUnderTest.addImportedFile(file).get())

        // Then
        assertTrue(UUID_PATTERN.matches(bookId), bookId)
        val book = assertNotNull(libraryBooks.getLibraryBookById(bookId))
        assertEquals("hash-a", book.sourceContentHash)
        val deviceFile = deviceFiles.files.value.single()
        assertEquals(bookId, deviceFile.libraryBookId)
        assertTrue(deviceFile.filePath in fileStore.files)
        assertTrue(file.stagedPath !in fileStore.files)
        val entry = libraryBooks.outbox.single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK, entry.entityType)
        assertEquals(bookId, entry.entityId)
    }

    @Test
    fun `a new book keeps the isbn of its file`() = runTest {
        // Given
        val file = stage("with isbn", hash = "hash-isbn", isbn = "9780261102217")

        // When
        val bookId = requireNotNull(classUnderTest.addImportedFile(file).get())

        // Then
        val book = assertNotNull(libraryBooks.getLibraryBookById(bookId))
        assertEquals("""{"isbn":"9780261102217"}""", book.metadataJson)
        assertEquals("9780261102217", classUnderTest.getLibraryBook(bookId)?.isbn)
    }

    @Test
    fun `the same hash imported twice returns the same book and adds nothing`() = runTest {
        // Given
        val firstId = requireNotNull(classUnderTest.addImportedFile(stage("book", "hash-a")).get())
        val again = stage("book", "hash-a", stagedPath = "/staging/again.epub")

        // When
        val secondId = classUnderTest.addImportedFile(again).get()

        // Then
        assertEquals(firstId, secondId)
        assertEquals(1, libraryBooks.books.value.size)
        assertEquals(1, deviceFiles.files.value.size)
        assertEquals(1, libraryBooks.outbox.size)
        assertTrue(again.stagedPath !in fileStore.files)
    }

    @Test
    fun `a hash already in Parrot Cloud attaches to that book`() = runTest {
        // Given
        libraryBooks.upsertLibraryBook(testLibraryBook(PARROT_BOOK_ID))
        cloudFiles.upsertFileState(parrotFile(PARROT_BOOK_ID, hash = "hash-p"))

        // When
        val bookId = classUnderTest.addImportedFile(stage("book", "hash-p")).get()

        // Then
        assertEquals(PARROT_BOOK_ID, bookId)
        assertEquals(1, libraryBooks.books.value.size)
        assertEquals(PARROT_BOOK_ID, deviceFiles.files.value.single().libraryBookId)
        assertTrue(libraryBooks.outbox.isEmpty())
    }

    @Test
    fun `a hash matching only a book's source hash attaches to that book`() = runTest {
        // Given
        libraryBooks.upsertLibraryBook(testLibraryBook(PARROT_BOOK_ID, sourceContentHash = "hash-s"))

        // When
        val bookId = classUnderTest.addImportedFile(stage("book", "hash-s")).get()

        // Then
        assertEquals(PARROT_BOOK_ID, bookId)
        assertEquals(PARROT_BOOK_ID, deviceFiles.files.value.single().libraryBookId)
        assertTrue(libraryBooks.outbox.isEmpty())
    }

    @Test
    fun `another media type of a matched book adds a second device file`() = runTest {
        // Given
        libraryBooks.upsertLibraryBook(testLibraryBook(PARROT_BOOK_ID, sourceContentHash = "hash-s"))
        classUnderTest.addImportedFile(stage("ebook", "hash-s"))
        val readAloud = stage("read-aloud", "hash-s", mediaType = "readaloud")

        // When
        val bookId = classUnderTest.addImportedFile(readAloud).get()

        // Then
        assertEquals(PARROT_BOOK_ID, bookId)
        assertEquals(
            setOf("ebook", "readaloud"),
            deviceFiles.files.value.map { file -> file.mediaType }.toSet(),
        )
    }

    @Test
    fun `deleting a book from the device removes its rows and files`() = runTest {
        // Given
        val bookId = requireNotNull(classUnderTest.addImportedFile(stage("book", "hash-a")).get())
        val path = deviceFiles.files.value.single().filePath

        // When
        classUnderTest.deleteBookFromDevice(bookId)

        // Then
        assertEquals(listOf(bookId), libraryBooks.deletedFromDevice)
        assertTrue(path !in fileStore.files)
        assertTrue(deviceFiles.files.value.isEmpty())
    }

    private fun stage(
        content: String,
        hash: String,
        mediaType: String = "ebook",
        stagedPath: String = "/staging/$content.epub",
        isbn: String? = null,
    ): ImportedFileCandidate {
        fileStore.files[stagedPath] = content.encodeToByteArray()
        return ImportedFileCandidate(
            stagedPath = stagedPath,
            mediaType = mediaType,
            fileSize = content.length.toLong(),
            contentHash = hash,
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            metadata = EpubMetadata(
                title = content,
                author = null,
                description = null,
                coverBytes = null,
                hasMediaOverlays = mediaType == "readaloud",
                publicationDate = null,
                isbn = isbn,
            ),
        )
    }

    private fun parrotFile(bookId: String, hash: String) = CloudBookFileEntity(
        libraryBookId = bookId,
        cloudBookFileId = "parrot-file",
        mediaType = "ebook",
        relativePath = "",
        fileName = "book.epub",
        status = "available",
        sizeBytes = 4,
        contentHash = hash,
        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
        remoteRevision = 1,
        updatedAt = "now",
    )

    private object DirectDatabaseExecutor : DatabaseExecutor {
        override suspend fun <T> executeDatabaseOperation(
            reportException: Boolean,
            operation: suspend () -> T,
        ): AppResult<T> = try {
            Ok(operation())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    private companion object {
        const val PARROT_BOOK_ID = "99999999-9999-4999-8999-999999999999"
        val UUID_PATTERN = Regex("^[0-9a-f-]{36}$")
    }
}
