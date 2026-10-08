package com.retro99.books.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.base.result.AppError
import com.retro99.books.data.source.AddedLibraryFile
import com.retro99.books.domain.BookFileOrigin
import com.retro99.books.domain.BookFileProvenance
import com.retro99.books.domain.StagedBookFile
import com.retro99.books.domain.StagedBookImportOutcome
import com.retro99.books.domain.StagedBookImportResult
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StagedBookImporterTest {

    private val metadataExtractor = FakeEpubMetadataExtractor()
    private val librarySource = RecordingLibraryLocalSource()
    private val classUnderTest = StagedBookImporter(metadataExtractor, librarySource)
    private val stagedPaths = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        stagedPaths.forEach(TestFiles::delete)
    }

    @Test
    fun `a staged file is sized and hashed from disk and handed over with its metadata`() = runTest {
        // Given
        val bytes = ByteArray(3 * HASH_BUFFER_SIZE + 17) { index -> (index % 251).toByte() }
        val path = stage("large.epub", bytes)
        metadataExtractor.metadata = testEpubMetadata(title = "Dune")

        // When
        val result = classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.Import))

        // Then
        assertEquals(
            StagedBookImportResult("book-id", "ebook", StagedBookImportOutcome.NewBook),
            result.get(),
        )
        val candidate = librarySource.candidates.single()
        assertEquals(path, candidate.stagedPath)
        assertEquals(bytes.size.toLong(), candidate.fileSize)
        assertEquals(sha256(bytes).toHexString(), candidate.contentHash)
        assertEquals(CONTENT_HASH_ALGORITHM, candidate.contentHashAlgorithm)
        assertEquals("Dune", candidate.metadata.title)
        assertEquals(listOf(path), metadataExtractor.requestedPaths)
    }

    @Test
    fun `the file picker origin is stored as import with no provenance`() = runTest {
        // Given
        val path = stage("picked.epub", "picked".encodeToByteArray())

        // When
        classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.Import))

        // Then
        val candidate = librarySource.candidates.single()
        assertEquals("import", candidate.origin)
        assertNull(candidate.provenance)
    }

    @Test
    fun `the catalogue origin and its provenance are carried to the library`() = runTest {
        // Given
        val path = stage("catalogue.epub", "catalogue".encodeToByteArray())
        val provenance = BookFileProvenance(sourceId = "source-1", publicationKey = "urn:book:1")

        // When
        classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.CatalogueDownload, provenance))

        // Then
        val candidate = librarySource.candidates.single()
        assertEquals("catalogue_download", candidate.origin)
        assertEquals(provenance, candidate.provenance)
    }

    @Test
    fun `a file with media overlays is a read-aloud`() = runTest {
        // Given
        val path = stage("overlay.epub", "overlay".encodeToByteArray())
        metadataExtractor.metadata = testEpubMetadata(hasMediaOverlays = true)

        // When
        val result = classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.Import))

        // Then
        assertEquals("readaloud", result.get()?.mediaType)
        assertEquals("readaloud", librarySource.candidates.single().mediaType)
    }

    @Test
    fun `an exact-byte match is reported as the existing book`() = runTest {
        // Given
        val path = stage("known.epub", "known".encodeToByteArray())
        librarySource.result = Ok(AddedLibraryFile("existing-id", isNewBook = false))

        // When
        val result = classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.CatalogueDownload))

        // Then
        assertEquals(
            StagedBookImportResult("existing-id", "ebook", StagedBookImportOutcome.ExistingBook),
            result.get(),
        )
    }

    @Test
    fun `an empty file is rejected before its metadata is read and is left in place`() = runTest {
        // Given
        val path = stage("empty.epub", byteArrayOf())

        // When
        val result = classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.Import))

        // Then
        assertNotNull(result.getError())
        assertTrue(metadataExtractor.requestedPaths.isEmpty())
        assertTrue(librarySource.candidates.isEmpty())
        assertTrue(TestFiles.exists(path))
    }

    @Test
    fun `a missing file is rejected`() = runTest {
        // When
        val result = classUnderTest.importStagedEpub(
            StagedBookFile("/no/such/dir/missing.epub", BookFileOrigin.Import),
        )

        // Then
        assertNotNull(result.getError())
        assertTrue(librarySource.candidates.isEmpty())
    }

    @Test
    fun `a file the metadata reader rejects never reaches the library and is left in place`() = runTest {
        // Given
        val path = stage("not-a-book.epub", "plain text".encodeToByteArray())
        val failure = AppError.UnknownError(Throwable("Failed to open EPUB"))
        metadataExtractor.failure = failure

        // When
        val result = classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.Import))

        // Then
        assertEquals(failure, result.getError())
        assertTrue(librarySource.candidates.isEmpty())
        assertTrue(TestFiles.exists(path))
    }

    @Test
    fun `a library error is returned as it is`() = runTest {
        // Given
        val path = stage("db-error.epub", "bytes".encodeToByteArray())
        val failure = AppError.UnknownError(Throwable("database"))
        librarySource.result = Err(failure)

        // When
        val result = classUnderTest.importStagedEpub(StagedBookFile(path, BookFileOrigin.Import))

        // Then
        assertEquals(failure, result.getError())
    }

    private fun stage(name: String, bytes: ByteArray): String =
        TestFiles.write(name, bytes).also { path -> stagedPaths += path }
}
