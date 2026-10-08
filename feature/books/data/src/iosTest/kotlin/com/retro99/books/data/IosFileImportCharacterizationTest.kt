package com.retro99.books.data

import com.github.michaelbull.result.get
import com.retro99.base.result.AppError
import com.retro99.books.data.source.LibraryLocalDataSource
import com.retro99.books.data.transfer.IosBookFileTransferFileStore
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the iOS file picker import does today, on real files. Written before the
 * staged-import extraction; these tests must keep passing unchanged.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileImportCharacterizationTest {

    private val fileManager = NSFileManager.defaultManager
    private val pickedDir = "${NSTemporaryDirectory()}picked-${NSUUID().UUIDString}"
    private val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .first() as String
    private val libraryDir = "$documents/library"
    private val coversDir = "$documents/imported_covers"

    private val deviceFiles = FakeDeviceFilesDatabase()
    private val libraryBooks = FakeLibraryBooksDatabase(deviceFiles = deviceFiles)
    private val metadataExtractor = FakeEpubMetadataExtractor()
    private val classUnderTest = IosFileImportManager(
        metadataExtractor = metadataExtractor,
        libraryLocalSource = LibraryLocalDataSource(
            libraryBooksDatabase = libraryBooks,
            deviceFilesDatabase = deviceFiles,
            cloudFilesDatabase = InMemoryCloudFilesDatabase(),
            databaseExecutor = DirectDatabaseExecutor,
            fileStore = IosBookFileTransferFileStore(),
        ),
    )

    @BeforeTest
    fun setUp() {
        cleanStores()
        fileManager.createDirectoryAtPath(pickedDir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    @AfterTest
    fun tearDown() {
        cleanStores()
        fileManager.removeItemAtPath(pickedDir, error = null)
    }

    @Test
    fun `a picked file is copied into the library store and the picked file is left alone`() = runTest {
        // Given
        val bytes = "picked epub bytes".encodeToByteArray()
        val picked = pick("book.epub", bytes)
        metadataExtractor.metadata = testEpubMetadata(title = "Dune", coverBytes = byteArrayOf(4, 5))

        // When
        val imported = assertNotNull(classUnderTest.importEpubFile(platformFile(picked)).get())

        // Then
        assertEquals("ebook", imported.mediaType)
        val deviceFile = deviceFiles.files.value.single()
        assertEquals(imported.libraryBookId, deviceFile.libraryBookId)
        assertEquals("import", deviceFile.origin)
        assertEquals(sha256(bytes).toHexString(), deviceFile.contentHash)
        assertEquals(CONTENT_HASH_ALGORITHM, deviceFile.contentHashAlgorithm)
        assertEquals(bytes.size.toLong(), deviceFile.fileSize)
        assertEquals("$libraryDir/${imported.libraryBookId}_ebook.epub", deviceFile.filePath)
        assertContentEquals(bytes, read(deviceFile.filePath))
        assertContentEquals(bytes, read(picked))

        val book = assertNotNull(libraryBooks.getLibraryBookById(imported.libraryBookId))
        assertEquals("Dune", book.title)
        assertEquals("$coversDir/${imported.libraryBookId}.png", book.coverPath)
        assertContentEquals(byteArrayOf(4, 5), read("$coversDir/${imported.libraryBookId}.png"))
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `metadata is read from a staged copy in the temporary directory that ends in tmp epub`() = runTest {
        // Given
        val picked = pick("book.epub", "bytes".encodeToByteArray())

        // When
        classUnderTest.importEpubFile(platformFile(picked))

        // Then
        val stagedPath = metadataExtractor.requestedPaths.single()
        assertTrue(stagedPath.startsWith(NSTemporaryDirectory()), stagedPath)
        assertTrue(stagedPath.endsWith(".tmp.epub"), stagedPath)
    }

    @Test
    fun `a file with media overlays is imported as a read-aloud`() = runTest {
        // Given
        val picked = pick("read-aloud.epub", "overlay bytes".encodeToByteArray())
        metadataExtractor.metadata = testEpubMetadata(hasMediaOverlays = true)

        // When
        val imported = assertNotNull(classUnderTest.importEpubFile(platformFile(picked)).get())

        // Then
        assertEquals("readaloud", imported.mediaType)
        assertEquals("readaloud", deviceFiles.files.value.single().mediaType)
        assertTrue(fileManager.fileExistsAtPath("$libraryDir/${imported.libraryBookId}_readaloud.epub"))
    }

    @Test
    fun `picking the same bytes twice returns the same book and stores one file`() = runTest {
        // Given
        val bytes = "same bytes".encodeToByteArray()
        val first = assertNotNull(classUnderTest.importEpubFile(platformFile(pick("a.epub", bytes))).get())

        // When
        val second = classUnderTest.importEpubFile(platformFile(pick("b.epub", bytes))).get()

        // Then
        assertEquals(first, second)
        assertEquals(1, libraryBooks.books.value.size)
        assertEquals(1, deviceFiles.files.value.size)
        assertEquals(1, libraryBooks.outbox.size)
        assertEquals(1, list(libraryDir).size)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `a file the metadata reader rejects leaves no rows and no files`() = runTest {
        // Given
        val picked = pick("not-a-book.epub", "plain text".encodeToByteArray())
        metadataExtractor.failure = AppError.UnknownError(Throwable("Failed to open EPUB"))

        // When
        val result = classUnderTest.importEpubFile(platformFile(picked))

        // Then
        assertNull(result.get())
        assertNoLibraryData()
        assertTrue(fileManager.fileExistsAtPath(picked))
    }

    @Test
    fun `an empty file is rejected before metadata is read and leaves no rows and no files`() = runTest {
        // Given
        val picked = pick("empty.epub", byteArrayOf())

        // When
        val result = classUnderTest.importEpubFile(platformFile(picked))

        // Then
        assertNull(result.get())
        assertTrue(metadataExtractor.requestedPaths.isEmpty())
        assertNoLibraryData()
    }

    @Test
    fun `a picked file that cannot be copied is an error and leaves no rows and no files`() = runTest {
        // When
        val result = classUnderTest.importEpubFile(platformFile("$pickedDir/missing.epub"))

        // Then
        assertNull(result.get())
        assertNoLibraryData()
    }

    private fun assertNoLibraryData() {
        assertTrue(libraryBooks.books.value.isEmpty())
        assertTrue(deviceFiles.files.value.isEmpty())
        assertTrue(libraryBooks.outbox.isEmpty())
        assertTrue(stagedFiles().isEmpty())
        assertTrue(list(libraryDir).isEmpty())
        assertTrue(list(coversDir).isEmpty())
    }

    private fun platformFile(path: String) = PlatformFile(NSURL.fileURLWithPath(path))

    private fun pick(name: String, bytes: ByteArray): String {
        val path = "$pickedDir/$name"
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        }
        check(fileManager.createFileAtPath(path, contents = data, attributes = null))
        return path
    }

    private fun read(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path) ?: error("No file at $path")
        if (data.length == 0UL) return byteArrayOf()
        return requireNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt())
    }

    private fun list(directory: String): List<String> =
        fileManager.contentsOfDirectoryAtPath(directory, error = null).orEmpty().map { name -> name.toString() }

    private fun stagedFiles(): List<String> =
        list(NSTemporaryDirectory()).filter { name -> name.endsWith(".tmp.epub") }

    private fun cleanStores() {
        fileManager.removeItemAtPath(libraryDir, error = null)
        fileManager.removeItemAtPath(coversDir, error = null)
        stagedFiles().forEach { name -> fileManager.removeItemAtPath("${NSTemporaryDirectory()}$name", error = null) }
    }
}
