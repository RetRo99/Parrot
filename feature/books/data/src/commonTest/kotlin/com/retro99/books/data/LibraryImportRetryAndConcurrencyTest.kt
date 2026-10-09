package com.retro99.books.data

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.books.data.source.AddedLibraryFile
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.books.data.transfer.BookFileTransferFileStore
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Finalizing a staged file is serialized, safe to retry and safe to cancel. */
class LibraryImportRetryAndConcurrencyTest {

    private val deviceFilesStore = FakeDeviceFilesDatabase()
    private val libraryBooksStore = FakeLibraryBooksDatabase(deviceFiles = deviceFilesStore)
    private val deviceFiles = InterruptibleDeviceFilesDatabase(deviceFilesStore)
    private val libraryBooks = InterruptibleLibraryBooksDatabase(libraryBooksStore)
    private val memoryFiles = InMemoryFileStore()
    private val fileStore = FailingDeleteFileStore(memoryFiles)
    private val classUnderTest = LibraryLocalDataSource(
        libraryBooksDatabase = libraryBooks,
        deviceFilesDatabase = deviceFiles,
        cloudFilesDatabase = InMemoryCloudFilesDatabase(),
        databaseExecutor = DirectDatabaseExecutor,
        fileStore = fileStore,
        importJournal = FakeLibraryImportJournal(),
    )

    @Test
    fun `two identical staged files finalized at the same time become one book`() = runTest {
        // Given
        val bytes = "same bytes".encodeToByteArray()
        val first = stage(bytes, "/staging/first.epub")
        val second = stage(bytes, "/staging/second.epub")
        libraryBooks.yieldAfterLastLookup = true

        // When
        val results = listOf(first, second)
            .map { file -> async { classUnderTest.addStagedFile(file).get() } }
            .awaitAll()
            .map { added -> assertNotNull(added) }

        // Then
        assertEquals(1, results.map(AddedLibraryFile::libraryBookId).distinct().size)
        assertEquals(listOf(false, true), results.map(AddedLibraryFile::isNewBook).sorted())
        assertEquals(1, libraryBooksStore.books.value.size)
        assertEquals(1, deviceFilesStore.files.value.size)
        assertEquals(1, libraryBooksStore.outbox.size)
        assertEquals(setOf(deviceFilesStore.files.value.single().filePath), memoryFiles.files.keys)
    }

    @Test
    fun `the same staged file finalized twice at the same time becomes one book`() = runTest {
        // Given
        val file = stage("once".encodeToByteArray())
        libraryBooks.yieldAfterLastLookup = true

        // When
        val results = List(2) { async { classUnderTest.addStagedFile(file).get() } }.awaitAll()

        // Then
        assertEquals(1, results.map { added -> assertNotNull(added).libraryBookId }.distinct().size)
        assertEquals(1, libraryBooksStore.books.value.size)
        assertEquals(1, deviceFilesStore.files.value.size)
        assertEquals(1, libraryBooksStore.outbox.size)
    }

    @Test
    fun `finalizing the same staged file again returns the same book and changes nothing`() = runTest {
        // Given
        val file = stage("again".encodeToByteArray())
        val first = assertNotNull(classUnderTest.addStagedFile(file).get())
        val booksBefore = libraryBooksStore.books.value
        val filesBefore = deviceFilesStore.files.value
        val storedBefore = memoryFiles.files.toMap()

        // When
        val second = assertNotNull(classUnderTest.addStagedFile(file).get())

        // Then
        assertEquals(AddedLibraryFile(first.libraryBookId, isNewBook = true), first)
        assertEquals(AddedLibraryFile(first.libraryBookId, isNewBook = false), second)
        assertEquals(booksBefore, libraryBooksStore.books.value)
        assertEquals(filesBefore, deviceFilesStore.files.value)
        assertEquals(storedBefore.keys, memoryFiles.files.keys)
        assertEquals(1, libraryBooksStore.outbox.size)
    }

    @Test
    fun `a new book whose database write fails leaves nothing behind and can be retried`() = runTest {
        // Given
        val bytes = "retry me".encodeToByteArray()
        val file = stage(bytes, metadata = testEpubMetadata(coverBytes = byteArrayOf(7)))
        libraryBooks.failInserts = true

        // When
        val failed = classUnderTest.addStagedFile(file)

        // Then: no rows, no orphan, and the staged bytes are back for a retry
        assertNotNull(failed.getError())
        assertTrue(libraryBooksStore.books.value.isEmpty())
        assertTrue(deviceFilesStore.files.value.isEmpty())
        assertTrue(libraryBooksStore.outbox.isEmpty())
        assertEquals(setOf(file.stagedPath), memoryFiles.files.keys)
        assertContentEquals(bytes, memoryFiles.files[file.stagedPath])

        // When
        libraryBooks.failInserts = false
        val retried = assertNotNull(classUnderTest.addStagedFile(file).get())

        // Then
        assertTrue(retried.isNewBook)
        assertEquals(1, libraryBooksStore.books.value.size)
        assertRowsPointAtFiles()
        assertEquals(1, libraryBooksStore.outbox.size)
        assertTrue(file.stagedPath !in memoryFiles.files)
    }

    @Test
    fun `attaching to a known book whose database write fails leaves nothing behind and can be retried`() = runTest {
        // Given
        val bytes = "attach me".encodeToByteArray()
        libraryBooksStore.upsertLibraryBook(
            testLibraryBook(KNOWN_BOOK_ID, sourceContentHash = sha256(bytes).toHexString()),
        )
        val file = stage(bytes)
        deviceFiles.failUpserts = true

        // When
        val failed = classUnderTest.addStagedFile(file)

        // Then
        assertNotNull(failed.getError())
        assertTrue(deviceFilesStore.files.value.isEmpty())
        assertEquals(setOf(file.stagedPath), memoryFiles.files.keys)

        // When
        deviceFiles.failUpserts = false
        val retried = classUnderTest.addStagedFile(file).get()

        // Then
        assertEquals(AddedLibraryFile(KNOWN_BOOK_ID, isNewBook = false), retried)
        assertRowsPointAtFiles()
        assertTrue(file.stagedPath !in memoryFiles.files)
    }

    @Test
    fun `a failed move leaves no cover behind`() = runTest {
        // Given: the staged file is gone before it can be moved
        val file = stage("vanished".encodeToByteArray(), metadata = testEpubMetadata(coverBytes = byteArrayOf(7)))
        memoryFiles.files.remove(file.stagedPath)

        // When
        val result = classUnderTest.addStagedFile(file)

        // Then
        assertNotNull(result.getError())
        assertTrue(memoryFiles.files.isEmpty())
        assertTrue(libraryBooksStore.books.value.isEmpty())
    }

    @Test
    fun `a new book whose cleanup fails after the database write is still imported and retry is a no-op`() = runTest {
        // Given
        val file = stage("cleanup fails".encodeToByteArray())
        fileStore.failDeletes = true

        // When
        val first = assertNotNull(classUnderTest.addStagedFile(file).get())

        // Then
        assertTrue(first.isNewBook)
        assertRowsPointAtFiles()

        // When
        fileStore.failDeletes = false
        val retried = classUnderTest.addStagedFile(file).get()

        // Then
        assertEquals(AddedLibraryFile(first.libraryBookId, isNewBook = false), retried)
        assertEquals(1, libraryBooksStore.books.value.size)
        assertEquals(setOf(deviceFilesStore.files.value.single().filePath), memoryFiles.files.keys)
    }

    @Test
    fun `an exact match whose staged copy cannot be deleted still succeeds and retry removes the copy`() = runTest {
        // Given
        val bytes = "duplicate".encodeToByteArray()
        val firstId = assertNotNull(classUnderTest.addStagedFile(stage(bytes)).get()).libraryBookId
        val duplicate = stage(bytes, "/staging/duplicate.epub")
        fileStore.failDeletes = true

        // When
        val matched = classUnderTest.addStagedFile(duplicate).get()

        // Then
        assertEquals(AddedLibraryFile(firstId, isNewBook = false), matched)
        assertRowsPointAtFiles()
        assertTrue(duplicate.stagedPath in memoryFiles.files)

        // When
        fileStore.failDeletes = false
        val retried = classUnderTest.addStagedFile(duplicate).get()

        // Then
        assertEquals(AddedLibraryFile(firstId, isNewBook = false), retried)
        assertEquals(setOf(deviceFilesStore.files.value.single().filePath), memoryFiles.files.keys)
        assertEquals(1, libraryBooksStore.books.value.size)
    }

    @Test
    fun `cancelling after the database write committed keeps the file the row points at`() = runTest {
        // Given: the insert commits, then notices the cancellation on its way out
        val file = stage("cancelled late".encodeToByteArray())
        libraryBooks.committed = CompletableDeferred()
        libraryBooks.resumeAfterCommit = CompletableDeferred()

        // When
        val job = launch { classUnderTest.addStagedFile(file) }
        libraryBooks.committed?.await()
        job.cancel()
        libraryBooks.resumeAfterCommit?.complete(Unit)
        job.join()

        // Then
        assertEquals(1, libraryBooksStore.books.value.size)
        assertRowsPointAtFiles()
    }

    @Test
    fun `an exact match keeps the existing book row and device file untouched`() = runTest {
        // Given: a book you have opened, synced and read
        val bytes = "being read".encodeToByteArray()
        val hash = sha256(bytes).toHexString()
        val book = testLibraryBook(KNOWN_BOOK_ID, remoteRevision = 7, sourceContentHash = hash, coverPath = "/c.png")
            .copy(lastOpenedAt = "2026-10-05T00:00:00Z", metadataJson = """{"isbn":"9780441013593"}""")
        libraryBooksStore.upsertLibraryBook(book)
        val existingFile = testDeviceFile(KNOWN_BOOK_ID, contentHash = hash, fileSize = bytes.size.toLong())
        deviceFilesStore.upsertDeviceFile(existingFile)
        memoryFiles.files[existingFile.filePath] = bytes
        libraryBooks.bookWrites = 0
        val again = stage(bytes, origin = DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD)

        // When
        val matched = classUnderTest.addStagedFile(again).get()

        // Then: only the staged duplicate is gone. Positions, bookmarks and highlights are
        // keyed by the book id in other tables, which this path has no handle on.
        assertEquals(AddedLibraryFile(KNOWN_BOOK_ID, isNewBook = false), matched)
        assertEquals(book, libraryBooksStore.books.value.values.single())
        assertEquals(0, libraryBooks.bookWrites)
        assertEquals(listOf(existingFile), deviceFilesStore.files.value)
        assertEquals(setOf(existingFile.filePath), memoryFiles.files.keys)
        assertTrue(libraryBooksStore.outbox.isEmpty())
    }

    private suspend fun assertRowsPointAtFiles() {
        val files = deviceFilesStore.files.value
        assertTrue(files.isNotEmpty())
        files.forEach { file -> assertTrue(file.filePath in memoryFiles.files, file.filePath) }
        val libraryPaths = memoryFiles.files.keys.filter { path -> path.startsWith("/library/") }
        assertEquals(files.map(DeviceFileEntity::filePath).toSet(), libraryPaths.toSet())
    }

    private fun stage(
        bytes: ByteArray,
        stagedPath: String = "/staging/${sha256(bytes).toHexString()}.epub",
        metadata: EpubMetadata = testEpubMetadata(),
        origin: String = DeviceFileEntity.ORIGIN_IMPORT,
    ): ImportedFileCandidate {
        memoryFiles.files[stagedPath] = bytes
        return ImportedFileCandidate(
            stagedPath = stagedPath,
            mediaType = "ebook",
            fileSize = bytes.size.toLong(),
            contentHash = sha256(bytes).toHexString(),
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            metadata = metadata,
            origin = origin,
        )
    }

    private class InterruptibleDeviceFilesDatabase(
        private val delegate: DeviceFilesDatabase,
    ) : DeviceFilesDatabase by delegate {
        var failUpserts = false

        override suspend fun upsertDeviceFile(file: DeviceFileEntity) {
            if (failUpserts) error("Injected database failure")
            delegate.upsertDeviceFile(file)
        }
    }

    private class InterruptibleLibraryBooksDatabase(
        private val delegate: LibraryBooksDatabase,
    ) : LibraryBooksDatabase by delegate {
        var failInserts = false
        var yieldAfterLastLookup = false
        var bookWrites = 0
        var committed: CompletableDeferred<Unit>? = null
        var resumeAfterCommit: CompletableDeferred<Unit>? = null

        /** The last duplicate lookup: lets another import run between it and the write. */
        override suspend fun findLibraryBookBySourceHash(algorithm: String, hash: String): LibraryBookEntity? {
            val found = delegate.findLibraryBookBySourceHash(algorithm, hash)
            if (yieldAfterLastLookup) yield()
            return found
        }

        override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
            bookWrites++
            delegate.upsertLibraryBook(book)
        }

        override suspend fun insertImportedBook(
            book: LibraryBookEntity,
            file: DeviceFileEntity,
            outboxEntry: SyncOutboxEntry,
        ) {
            if (failInserts) error("Injected database failure")
            bookWrites++
            delegate.insertImportedBook(book, file, outboxEntry)
            // Like the real DAO: the transaction is committed, then the dispatcher switch
            // back reports a cancellation that arrived meanwhile.
            committed?.complete(Unit)
            resumeAfterCommit?.await()
            currentCoroutineContext().ensureActive()
        }
    }

    private class FailingDeleteFileStore(
        private val delegate: BookFileTransferFileStore,
    ) : BookFileTransferFileStore by delegate {
        var failDeletes = false

        override fun contentHash(path: String): String = delegate.contentHash(path)

        override suspend fun delete(path: String): Boolean {
            if (failDeletes) error("Injected delete failure")
            return delegate.delete(path)
        }
    }

    private companion object {
        const val KNOWN_BOOK_ID = "99999999-9999-4999-8999-999999999999"
    }
}
