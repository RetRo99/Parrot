package com.retro99.books.data

import com.github.michaelbull.result.get
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.books.data.transfer.BookFileTransferFileStore
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The process can die at any line of an import. Whatever it leaves, the next start settles:
 * no library file without a row, no row without its file. A death is modelled as an [Error],
 * which none of the import's own cleanup catches; "the next start" is a new data source over
 * the same disk and database.
 */
class LibraryImportJournalTest {

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooksStore = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val libraryBooks = DyingLibraryBooksDatabase(libraryBooksStore)
    private val disk = InMemoryFileStore()
    private val fileStore = DyingFileStore(disk)
    private val journal = FakeLibraryImportJournal()

    private fun newProcess() = LibraryLocalDataSource(
        libraryBooksDatabase = libraryBooks,
        deviceFilesDatabase = deviceFiles,
        cloudFilesDatabase = InMemoryCloudFilesDatabase(),
        databaseExecutor = DirectDatabaseExecutor,
        fileStore = fileStore,
        importJournal = journal,
    )

    @Test
    fun `an import that finishes leaves the journal empty`() = runTest {
        // Given
        val file = stage("finished")

        // When
        val added = assertNotNull(newProcess().addStagedFile(file).get())

        // Then
        assertTrue(journal.entries.isEmpty())
        assertEquals(setOf(libraryPath(added.libraryBookId), "/covers/${added.libraryBookId}.png"), disk.files.keys)
    }

    @Test
    fun `dying before the file moves leaves the staged file and nothing in the library`() = runTest {
        // Given
        val file = stage("before the move")
        fileStore.dieBeforeMove = true
        assertFailsWith<ProcessDied> { newProcess().addStagedFile(file) }
        fileStore.dieBeforeMove = false

        // When
        newProcess().reconcileInterruptedImports()

        // Then
        assertTrue(journal.entries.isEmpty())
        assertEquals(setOf(file.stagedPath), disk.files.keys)
        assertTrue(libraryBooksStore.books.value.isEmpty())
        assertTrue(deviceFiles.files.value.isEmpty())
    }

    @Test
    fun `dying after the file moved removes the file and cover no row knows about`() = runTest {
        // Given
        val file = stage("after the move")
        fileStore.dieAfterMove = true
        assertFailsWith<ProcessDied> { newProcess().addStagedFile(file) }
        fileStore.dieAfterMove = false
        assertEquals(2, disk.files.size, "the library file and its cover are on disk with no row")

        // When
        newProcess().reconcileInterruptedImports()

        // Then
        assertTrue(journal.entries.isEmpty())
        assertTrue(disk.files.isEmpty())
        assertTrue(libraryBooksStore.books.value.isEmpty())
        assertTrue(deviceFiles.files.value.isEmpty())
        assertTrue(libraryBooksStore.outbox.isEmpty())
    }

    @Test
    fun `dying after the rows were written keeps the book and its file`() = runTest {
        // Given
        val file = stage("after the database write")
        libraryBooks.dieAfterInsert = true
        assertFailsWith<ProcessDied> { newProcess().addStagedFile(file) }
        libraryBooks.dieAfterInsert = false
        val book = libraryBooksStore.books.value.values.single()

        // When
        newProcess().reconcileInterruptedImports()

        // Then
        assertTrue(journal.entries.isEmpty())
        val stored = deviceFiles.files.value.single()
        assertEquals(book.libraryBookId, stored.libraryBookId)
        assertTrue(stored.filePath in disk.files, "the row points at a file that exists")
        assertTrue(assertNotNull(book.coverPath) in disk.files)
        assertEquals(1, libraryBooksStore.outbox.size)
    }

    @Test
    fun `dying while a file is attached to an existing book leaves that book as it was`() = runTest {
        // Given: the book is known by its hash but has no file on this device
        val existing = testLibraryBook("existing-book", sourceContentHash = "hash-attach", coverPath = "/covers/kept.png")
        libraryBooksStore.upsertLibraryBook(existing)
        disk.files["/covers/kept.png"] = byteArrayOf(1)
        val file = stage("attach", hash = "hash-attach")
        fileStore.dieAfterMove = true
        assertFailsWith<ProcessDied> { newProcess().addStagedFile(file) }
        fileStore.dieAfterMove = false

        // When
        newProcess().reconcileInterruptedImports()

        // Then
        assertTrue(journal.entries.isEmpty())
        assertEquals(setOf("/covers/kept.png"), disk.files.keys)
        assertEquals(mapOf(existing.libraryBookId to existing), libraryBooksStore.books.value)
        assertTrue(deviceFiles.files.value.isEmpty())
    }

    @Test
    fun `the next import settles what an earlier process left before it starts`() = runTest {
        // Given
        fileStore.dieAfterMove = true
        assertFailsWith<ProcessDied> { newProcess().addStagedFile(stage("left behind")) }
        fileStore.dieAfterMove = false

        // When
        val added = assertNotNull(newProcess().addStagedFile(stage("next", hash = "hash-next")).get())

        // Then
        assertTrue(journal.entries.isEmpty())
        assertEquals(setOf(libraryPath(added.libraryBookId), "/covers/${added.libraryBookId}.png"), disk.files.keys)
        assertEquals(setOf(added.libraryBookId), libraryBooksStore.books.value.keys)
    }

    @Test
    fun `settling twice changes nothing the second time`() = runTest {
        // Given
        libraryBooks.dieAfterInsert = true
        assertFailsWith<ProcessDied> { newProcess().addStagedFile(stage("twice")) }
        libraryBooks.dieAfterInsert = false
        newProcess().reconcileInterruptedImports()
        val files = disk.files.keys.toSet()
        val books = libraryBooksStore.books.value

        // When
        newProcess().reconcileInterruptedImports()

        // Then
        assertEquals(files, disk.files.keys)
        assertEquals(books, libraryBooksStore.books.value)
    }

    @Test
    fun `a book is found by the bytes of its file on this device only`() = runTest {
        // Given
        val source = newProcess()
        val added = assertNotNull(source.addStagedFile(stage("on device", hash = "hash-device")).get())
        libraryBooksStore.upsertLibraryBook(testLibraryBook("cloud-only", sourceContentHash = "hash-cloud"))

        // Then
        assertEquals(added.libraryBookId, source.findBookWithDeviceFile(CONTENT_HASH_ALGORITHM, "hash-device"))
        assertNull(source.findBookWithDeviceFile(CONTENT_HASH_ALGORITHM, "hash-cloud"))
        assertNull(source.findBookWithDeviceFile(CONTENT_HASH_ALGORITHM, "hash-unknown"))
    }

    private fun libraryPath(libraryBookId: String) = "/library/${libraryBookId}_ebook.epub"

    private fun stage(name: String, hash: String = "hash-$name"): ImportedFileCandidate {
        val path = "/staging/$name.epub"
        disk.files[path] = name.encodeToByteArray()
        return ImportedFileCandidate(
            stagedPath = path,
            mediaType = "ebook",
            fileSize = name.length.toLong(),
            contentHash = hash,
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            metadata = testEpubMetadata(coverBytes = byteArrayOf(7)),
        )
    }

    private class ProcessDied : Error("The process died here")

    private class DyingFileStore(
        private val delegate: BookFileTransferFileStore,
    ) : BookFileTransferFileStore by delegate {
        var dieBeforeMove = false
        var dieAfterMove = false

        override fun contentHash(path: String): String = delegate.contentHash(path)

        override suspend fun moveToImportedStore(stagingPath: String, destinationPath: String) {
            if (dieBeforeMove) throw ProcessDied()
            delegate.moveToImportedStore(stagingPath, destinationPath)
            if (dieAfterMove) throw ProcessDied()
        }
    }

    private class DyingLibraryBooksDatabase(
        private val delegate: LibraryBooksDatabase,
    ) : LibraryBooksDatabase by delegate {
        var dieAfterInsert = false

        override suspend fun insertImportedBook(book: LibraryBookEntity, file: DeviceFileEntity, outboxEntry: SyncOutboxEntry) {
            delegate.insertImportedBook(book, file, outboxEntry)
            if (dieAfterInsert) throw ProcessDied()
        }
    }
}
