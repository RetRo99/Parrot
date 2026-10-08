package com.retro99.books.data

import android.content.ContextWrapper
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.books.data.transfer.AndroidBookFileTransferFileStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The retry guarantees of [LibraryLocalDataSource], on a real Android file store. */
class AndroidLibraryImportRetryDiskTest {

    private val root: File = Files.createTempDirectory("android-retry").toFile()
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooks = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val failingLibraryBooks = FailingInsertLibraryBooksDatabase(libraryBooks)
    private val classUnderTest = LibraryLocalDataSource(
        libraryBooksDatabase = failingLibraryBooks,
        deviceFilesDatabase = deviceFiles,
        cloudFilesDatabase = InMemoryCloudFilesDatabase(),
        databaseExecutor = DirectDatabaseExecutor,
        fileStore = AndroidBookFileTransferFileStore(TestContext(cacheDir, filesDir)),
    )

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a failed database write puts the file back in staging and a retry imports it`() = runTest {
        // Given
        val bytes = "retry on disk".encodeToByteArray()
        val staged = File(cacheDir, "download.epub").apply { writeBytes(bytes) }
        val candidate = ImportedFileCandidate(
            stagedPath = staged.absolutePath,
            mediaType = "ebook",
            fileSize = staged.length(),
            contentHash = calculateFileContentHash(staged.absolutePath),
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            metadata = testEpubMetadata(coverBytes = byteArrayOf(1)),
        )

        // When
        val failed = classUnderTest.addStagedFile(candidate)

        // Then
        assertNotNull(failed.getError())
        assertContentEquals(bytes, staged.readBytes())
        assertTrue(File(filesDir, "library").listFiles().orEmpty().isEmpty())
        assertTrue(File(filesDir, "imported_covers").listFiles().orEmpty().isEmpty())
        assertTrue(deviceFiles.files.value.isEmpty())

        // When
        failingLibraryBooks.failInserts = false
        val added = assertNotNull(classUnderTest.addStagedFile(candidate).get())

        // Then
        assertTrue(added.isNewBook)
        val libraryFile = File(deviceFiles.files.value.single().filePath)
        assertEquals(File(filesDir, "library/${added.libraryBookId}_ebook.epub"), libraryFile)
        assertContentEquals(bytes, libraryFile.readBytes())
        assertTrue(!staged.exists())
    }

    private class TestContext(
        private val cache: File,
        private val files: File,
    ) : ContextWrapper(null) {
        override fun getCacheDir(): File = cache
        override fun getFilesDir(): File = files
    }
}
