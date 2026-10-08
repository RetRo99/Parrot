package com.retro99.catalogue.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.BookFileOrigin
import com.retro99.books.domain.BookFileProvenance
import com.retro99.books.domain.StagedBookFile
import com.retro99.books.domain.StagedBookImportManager
import com.retro99.books.domain.StagedBookImportOutcome
import com.retro99.books.domain.StagedBookImportResult
import com.retro99.books.domain.StagedBookNotReadable
import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.catalogue.domain.StagedCatalogueBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryCatalogueBookAdderTest {
    private val library = FakeImportManager()
    private var openProfile: String? = "p1"
    private val classUnderTest = LibraryCatalogueBookAdder(lazy { library }) { openProfile }

    @Test
    fun `the staged file goes to the library import as a catalogue download with its provenance`() = runTest {
        // When
        val result = classUnderTest.add("p1", staged())

        // Then
        assertEquals(CatalogueBookAddResult.Added("lib-1"), result)
        assertEquals(
            StagedBookFile(
                path = "/staging/p1/generated.epub",
                origin = BookFileOrigin.CatalogueDownload,
                provenance = BookFileProvenance("source-1", "urn:book:1", "application/epub+zip#1"),
            ),
            library.imported.single(),
        )
    }

    @Test
    fun `bytes the library already holds are answered with that book`() = runTest {
        // Given
        library.result = Ok(StagedBookImportResult("existing", "ebook", StagedBookImportOutcome.ExistingBook))

        // When
        val result = classUnderTest.add("p1", staged())

        // Then
        assertEquals(CatalogueBookAddResult.Added("existing"), result)
    }

    @Test
    fun `a file the library cannot read is invalid and anything else is storage`() = runTest {
        // Given
        library.result = Err(AppError.UnknownError(StagedBookNotReadable("Failed to open EPUB")))

        // Then
        assertEquals(CatalogueBookAddResult.Failed(AcquisitionFailureReason.Invalid), classUnderTest.add("p1", staged()))

        listOf(
            AppError.UnknownError(Throwable("File is empty")),
            AppError.DatabaseError(Throwable("disk I/O error"), table = "library_books"),
            AppError.UnknownError(IllegalStateException("No space left on device")),
        ).forEach { deviceProblem ->
            // Given
            library.result = Err(deviceProblem)

            // Then
            assertEquals(CatalogueBookAddResult.Failed(AcquisitionFailureReason.Storage), classUnderTest.add("p1", staged()))
        }
    }

    @Test
    fun `nothing reaches the library for a profile that is not the open one`() = runTest {
        // Given
        openProfile = "p2"

        // Then
        assertFailsWith<CancellationException> { classUnderTest.add("p1", staged()) }
        assertFailsWith<CancellationException> { classUnderTest.settleInterruptedAdds("p1") }
        assertFailsWith<CancellationException> { classUnderTest.findAddedBook("p1", "abc") }
        assertTrue(library.imported.isEmpty())
        assertEquals(0, library.settled)
        assertTrue(library.lookedUp.isEmpty())
    }

    @Test
    fun `after a restart the library settles its imports and is asked by content hash`() = runTest {
        // Given
        library.onDevice["abc"] = "lib-9"

        // When
        classUnderTest.settleInterruptedAdds("p1")

        // Then
        assertEquals(1, library.settled)
        assertEquals("lib-9", classUnderTest.findAddedBook("p1", "abc"))
        assertEquals(null, classUnderTest.findAddedBook("p1", "other"))
    }

    private fun staged() = StagedCatalogueBook(
        requestId = "request-1",
        path = "/staging/p1/generated.epub",
        contentHash = "abc",
        sizeBytes = 3000,
        sourceId = "source-1",
        publicationKey = "urn:book:1",
        representationKey = "application/epub+zip#1",
        detailIdentity = "urn:entry:1",
        catalogueName = "Home shelf",
    )

    private class FakeImportManager : StagedBookImportManager {
        val imported = mutableListOf<StagedBookFile>()
        val lookedUp = mutableListOf<String>()
        val onDevice = mutableMapOf<String, String>()
        var settled = 0
        var result: AppResult<StagedBookImportResult> =
            Ok(StagedBookImportResult("lib-1", "ebook", StagedBookImportOutcome.NewBook))

        override suspend fun importStagedEpub(file: StagedBookFile): AppResult<StagedBookImportResult> {
            imported += file
            return result
        }

        override suspend fun settleInterruptedImports() {
            settled++
        }

        override suspend fun findBookOnDevice(contentSha256: String): String? {
            lookedUp += contentSha256
            return onDevice[contentSha256]
        }
    }
}
