package com.retro99.books.data

import android.content.Context
import android.content.ContextWrapper
import com.github.michaelbull.result.get
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.books.data.transfer.AndroidBookFileTransferFileStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins where an import ends up on an Android disk today. Written before the staged-import
 * extraction; these tests must keep passing unchanged.
 *
 * The picker entry point itself can't run here: FileKit's `PlatformFile` is Java 21
 * bytecode and host tests run on the project's JDK 17.
 */
class AndroidLibraryImportDiskCharacterizationTest {

    private val root: File = Files.createTempDirectory("android-import").toFile()
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val context: Context = TestContext(cacheDir, filesDir)

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooks = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val failingLibraryBooks = FailingInsertLibraryBooksDatabase(libraryBooks, failInserts = false)
    private val classUnderTest = LibraryLocalDataSource(
        libraryBooksDatabase = failingLibraryBooks,
        deviceFilesDatabase = deviceFiles,
        cloudFilesDatabase = InMemoryCloudFilesDatabase(),
        databaseExecutor = DirectDatabaseExecutor,
        fileStore = AndroidBookFileTransferFileStore(context),
    )

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a staged file is moved to files library and its cover to files imported_covers`() = runTest {
        // Given
        val bytes = "picked epub bytes".encodeToByteArray()
        val candidate = stage(bytes, testEpubMetadata(coverBytes = byteArrayOf(4, 5)))

        // When
        val bookId = assertNotNull(classUnderTest.addImportedFile(candidate).get())

        // Then
        val deviceFile = deviceFiles.files.value.single()
        val libraryFile = File(filesDir, "library/${bookId}_ebook.epub")
        assertEquals(libraryFile.absolutePath, deviceFile.filePath)
        assertContentEquals(bytes, libraryFile.readBytes())
        assertEquals(sha256(bytes).toHexString(), deviceFile.contentHash)
        assertEquals(bytes.size.toLong(), deviceFile.fileSize)
        val coverFile = File(filesDir, "imported_covers/$bookId.png")
        assertEquals(coverFile.absolutePath, libraryBooks.getLibraryBookById(bookId)?.coverPath)
        assertContentEquals(byteArrayOf(4, 5), coverFile.readBytes())
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `the same bytes staged again are deleted from the cache and one library file remains`() = runTest {
        // Given
        val bytes = "same bytes".encodeToByteArray()
        val firstId = assertNotNull(classUnderTest.addImportedFile(stage(bytes)).get())

        // When
        val secondId = classUnderTest.addImportedFile(stage(bytes)).get()

        // Then
        assertEquals(firstId, secondId)
        assertEquals(listOf("${firstId}_ebook.epub"), File(filesDir, "library").list().orEmpty().toList())
        assertContentEquals(bytes, File(filesDir, "library/${firstId}_ebook.epub").readBytes())
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a failed database write leaves nothing in the library or cover directories`() = runTest {
        // Given
        failingLibraryBooks.failInserts = true
        val candidate = stage("will fail".encodeToByteArray(), testEpubMetadata(coverBytes = byteArrayOf(9)))

        // When
        val result = classUnderTest.addImportedFile(candidate)

        // Then
        assertNull(result.get())
        assertTrue(File(filesDir, "library").listFiles().orEmpty().isEmpty())
        assertTrue(File(filesDir, "imported_covers").listFiles().orEmpty().isEmpty())
        assertTrue(deviceFiles.files.value.isEmpty())
    }

    private fun stage(bytes: ByteArray, metadata: EpubMetadata = testEpubMetadata()): ImportedFileCandidate {
        // Same staging name the picker uses.
        val staged = File(cacheDir, "${UUID.randomUUID()}.tmp.epub").apply { writeBytes(bytes) }
        return ImportedFileCandidate(
            stagedPath = staged.absolutePath,
            mediaType = "ebook",
            fileSize = staged.length(),
            contentHash = calculateFileContentHash(staged.absolutePath),
            contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
            metadata = metadata,
        )
    }

    private class TestContext(
        private val cache: File,
        private val files: File,
    ) : ContextWrapper(null) {
        override fun getCacheDir(): File = cache
        override fun getFilesDir(): File = files
    }
}
