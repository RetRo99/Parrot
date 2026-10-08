package com.retro99.books.data

import android.content.Context
import android.content.ContextWrapper
import com.github.michaelbull.result.get
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.base.result.AppError
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android picker import on real files, below `PlatformFile`: FileKit's class is Java 21
 * bytecode and host tests run on the project's JDK 17, so the picked file is copied by the
 * test instead.
 */
class AndroidFileImportManagerTest {

    private val root: File = Files.createTempDirectory("android-picker").toFile()
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val pickedDir = File(root, "picked").apply { mkdirs() }
    private val context: Context = TestContext(cacheDir, filesDir)

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooks = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val metadataExtractor = FakeEpubMetadataExtractor()
    private val analytics = RecordingAnalytics()
    private val classUnderTest = AndroidFileImportManager(
        context = context,
        metadataExtractor = metadataExtractor,
        libraryLocalSource = LibraryLocalDataSource(
            libraryBooksDatabase = libraryBooks,
            deviceFilesDatabase = deviceFiles,
            cloudFilesDatabase = InMemoryCloudFilesDatabase(),
            databaseExecutor = DirectDatabaseExecutor,
            fileStore = AndroidBookFileTransferFileStore(context),
        ),
        analytics = analytics,
    )

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a picked file is copied into the library store and the picked file is left alone`() = runTest {
        // Given
        val bytes = "picked epub bytes".encodeToByteArray()
        val picked = pick("book.epub", bytes)
        metadataExtractor.metadata = testEpubMetadata(title = "Dune", coverBytes = byteArrayOf(4, 5))

        // When
        val imported = assertNotNull(import(picked).get())

        // Then
        assertEquals("ebook", imported.mediaType)
        val deviceFile = deviceFiles.files.value.single()
        assertEquals(imported.libraryBookId, deviceFile.libraryBookId)
        assertEquals("import", deviceFile.origin)
        assertEquals(sha256(bytes).toHexString(), deviceFile.contentHash)
        assertEquals(CONTENT_HASH_ALGORITHM, deviceFile.contentHashAlgorithm)
        assertEquals(bytes.size.toLong(), deviceFile.fileSize)
        val libraryFile = File(filesDir, "library/${imported.libraryBookId}_ebook.epub")
        assertEquals(libraryFile.absolutePath, deviceFile.filePath)
        assertContentEquals(bytes, libraryFile.readBytes())
        assertContentEquals(bytes, picked.readBytes())
        assertEquals("Dune", libraryBooks.getLibraryBookById(imported.libraryBookId)?.title)
        assertEquals(1, libraryBooks.outbox.size)
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `metadata is read from a staged copy in the cache that ends in tmp epub`() = runTest {
        // When
        import(pick("book.epub", "bytes".encodeToByteArray()))

        // Then
        val stagedPath = metadataExtractor.requestedPaths.single()
        assertEquals(cacheDir.absolutePath, File(stagedPath).parentFile?.absolutePath)
        assertTrue(stagedPath.endsWith(".tmp.epub"), stagedPath)
    }

    @Test
    fun `a file with media overlays is imported as a read-aloud`() = runTest {
        // Given
        metadataExtractor.metadata = testEpubMetadata(hasMediaOverlays = true)

        // When
        val imported = assertNotNull(import(pick("read-aloud.epub", "overlay".encodeToByteArray())).get())

        // Then
        assertEquals("readaloud", imported.mediaType)
        assertTrue(File(filesDir, "library/${imported.libraryBookId}_readaloud.epub").exists())
    }

    @Test
    fun `picking the same bytes twice returns the same book and stores one file`() = runTest {
        // Given
        val bytes = "same bytes".encodeToByteArray()
        val first = assertNotNull(import(pick("a.epub", bytes)).get())

        // When
        val second = import(pick("b.epub", bytes)).get()

        // Then
        assertEquals(first, second)
        assertEquals(1, libraryBooks.books.value.size)
        assertEquals(1, libraryBooks.outbox.size)
        assertEquals(1, File(filesDir, "library").listFiles().orEmpty().size)
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a file the metadata reader rejects leaves no rows and no files`() = runTest {
        // Given
        val picked = pick("not-a-book.epub", "plain text".encodeToByteArray())
        metadataExtractor.failure = AppError.UnknownError(Throwable("Failed to open EPUB"))

        // When
        val result = import(picked)

        // Then
        assertNull(result.get())
        assertNoLibraryData()
        assertTrue(picked.exists())
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun `an empty file is rejected before metadata is read and leaves no rows and no files`() = runTest {
        // When
        val result = import(pick("empty.epub", byteArrayOf()))

        // Then
        assertNull(result.get())
        assertTrue(metadataExtractor.requestedPaths.isEmpty())
        assertNoLibraryData()
    }

    @Test
    fun `a picked file that cannot be read is reported and leaves no rows and no files`() = runTest {
        // When
        val result = import(File(pickedDir, "missing.epub"))

        // Then
        assertNull(result.get())
        assertNoLibraryData()
        assertEquals(listOf<String?>("AndroidFileImportManager: Error importing EPUB"), analytics.exceptions)
    }

    private suspend fun import(picked: File) =
        classUnderTest.importCopy { stagedFile -> picked.copyTo(stagedFile, overwrite = true) }

    private fun assertNoLibraryData() {
        assertTrue(libraryBooks.books.value.isEmpty())
        assertTrue(deviceFiles.files.value.isEmpty())
        assertTrue(libraryBooks.outbox.isEmpty())
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        assertTrue(File(filesDir, "library").listFiles().orEmpty().isEmpty())
        assertTrue(File(filesDir, "imported_covers").listFiles().orEmpty().isEmpty())
    }

    private fun pick(name: String, bytes: ByteArray): File =
        File(pickedDir, name).apply { writeBytes(bytes) }

    private class TestContext(
        private val cache: File,
        private val files: File,
    ) : ContextWrapper(null) {
        override fun getCacheDir(): File = cache
        override fun getFilesDir(): File = files
    }

    private class RecordingAnalytics : Analytics {
        val exceptions = mutableListOf<String?>()
        override fun logEvent(event: AnalyticsEvent) = Unit
        override fun logException(throwable: Throwable, message: String?) {
            exceptions += message
        }
        override fun setUserId(userId: String?) = Unit
    }
}
