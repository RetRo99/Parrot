package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState.Adding
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Failed
import com.retro99.catalogue.domain.AcquisitionState.Interrupted
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity
import com.retro99.database.api.catalogue.CatalogueBookSourceEntity
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A checked download becomes a library book. "The process dies" is a step that never comes
 * back followed by a new queue over the same database and files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionAddToLibraryTest {

    @Test
    fun `an added book is recorded in order - book id then provenance then done`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        var rowWhenProvenanceIsWritten: CatalogueAcquisitionEntity? = null
        harness.sources.beforeInsert = { rowWhenProvenanceIsWritten = harness.row("book-1") }

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then: the book id was on the row, still "adding", before the provenance row
        val before = assertNotNull(rowWhenProvenanceIsWritten)
        assertEquals("lib-book-1", before.libraryBookId)
        assertEquals(CatalogueAcquisitionEntity.STATE_ADDING, before.state)
        assertNotNull(before.detailUrl)
        assertNotNull(before.stagingPath)

        // And afterwards: done, with the listing address and staging path cleared
        val row = harness.row("book-1")!!
        assertEquals(Done, harness.state("book-1"))
        assertEquals("lib-book-1", row.libraryBookId)
        assertEquals(harness.world.time, row.completedAt)
        assertNull(row.detailUrl)
        assertNull(row.stagingPath)
        assertTrue(harness.files.files.isEmpty())
        assertEquals(1, harness.sources.peek().size)
    }

    @Test
    fun `provenance names the catalogue by origin and never by its full address`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        val request = bookRequest(1).copy(rightsText = "Public domain in the USA.", catalogueUpdated = "2026-10-01T00:00:00Z")

        // When
        val queued = harness.queue.requestQueued(request)
        runCurrent()

        // Then
        assertEquals(
            CatalogueBookSourceEntity(
                id = queued.requestId,
                libraryBookId = "lib-book-1",
                sourceId = "source-1",
                catalogueName = "Home shelf",
                catalogueOrigin = "https://books.example:443",
                publicationKey = "book-1",
                detailIdentity = "urn:entry:1",
                selectedFormat = "application/epub+zip",
                rightsText = "Public domain in the USA.",
                catalogueUpdated = "2026-10-01T00:00:00Z",
                contentHash = "sha-3000-3000",
                acquiredAt = harness.world.time,
            ),
            harness.sources.peek().single(),
        )
    }

    @Test
    fun `a catalogue that is no longer registered is named by the origin of its listing`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.sourceAddresses.clear()

        // When
        harness.queue.request(bookRequest(1).copy(listingUrl = "http://shelf.local:8080/opds/new?key=abc"))
        runCurrent()

        // Then
        assertEquals("http://shelf.local:8080", harness.sources.peek().single().catalogueOrigin)
    }

    @Test
    fun `bytes the library already has attach provenance to that book`() = runTest {
        // Given: the library answers with a book it already had
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = { CatalogueBookAddResult.Added("already-in-library") }

        // When
        harness.queue.request(bookRequest(1))
        harness.queue.request(bookRequest(2))
        runCurrent()

        // Then: one book, a provenance row per publication it was acquired as
        assertEquals(mapOf("book-1" to Done, "book-2" to Done), harness.states())
        assertEquals(listOf("already-in-library", "already-in-library"), harness.sources.peek().map { it.libraryBookId })
        assertEquals(listOf("book-1", "book-2"), harness.sources.peek().map { it.publicationKey })
    }

    @Test
    fun `a book the library cannot add fails with its reason and the staged file is removed`() = runTest {
        listOf(
            CatalogueBookAddResult.Failed(AcquisitionFailureReason.Invalid) to null,
            CatalogueBookAddResult.Failed(AcquisitionFailureReason.Storage, neededBytes = 3000) to 3000L,
        ).forEach { (failure, needed) ->
            // Given
            val harness = QueueHarness(backgroundScope)
            harness.adder.result = { failure }

            // When
            harness.queue.request(bookRequest(1))
            runCurrent()

            // Then
            assertEquals(Failed(failure.reason), harness.state("book-1"))
            assertEquals(needed, harness.row("book-1")!!.neededBytes)
            assertEquals(needed, harness.queue.observeAcquisitions().first().single().neededBytes)
            assertNull(harness.row("book-1")!!.libraryBookId)
            assertNull(harness.row("book-1")!!.stagingPath)
            assertTrue(harness.files.files.isEmpty())
            assertTrue(harness.sources.peek().isEmpty())
        }
    }

    @Test
    fun `dying before the library touched the file leaves an interrupted request and no file`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.adder.dieBeforeAdding = true
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Adding, harness.state("book-1"))
        assertEquals(1, harness.files.files.size)
        val settledBefore = harness.adder.settled

        // When
        harness.restart().restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(Interrupted, harness.state("book-1"))
        assertTrue(harness.files.files.isEmpty())
        assertTrue(harness.sources.peek().isEmpty())
        assertEquals(settledBefore + 1, harness.adder.settled, "the library settles its half-done import first")
    }

    @Test
    fun `dying after the book reached the library finishes the request on the next start`() = runTest {
        // Given: the book is in the library, the request never heard of it
        val harness = QueueHarness(backgroundScope)
        harness.adder.dieAfterAdding = true
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Adding, harness.state("book-1"))
        assertNull(harness.row("book-1")!!.libraryBookId)

        // When
        harness.restart().restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertEquals("lib-book-1", harness.row("book-1")!!.libraryBookId)
        assertNull(harness.row("book-1")!!.detailUrl)
        assertEquals(listOf("lib-book-1"), harness.sources.peek().map { it.libraryBookId })
        assertEquals(1, harness.adder.calls.size, "the book is not added a second time")
        assertEquals(1, harness.source.calls.size, "and not downloaded a second time")
    }

    @Test
    fun `dying after the provenance write finishes the request with one provenance row`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.sources.dieAfterInsert = true
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Adding, harness.state("book-1"))
        assertEquals("lib-book-1", harness.row("book-1")!!.libraryBookId)
        assertEquals(1, harness.sources.peek().size)

        // When
        harness.restart().restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertEquals(1, harness.sources.peek().size)
        assertNull(harness.row("book-1")!!.detailUrl)
        assertNull(harness.row("book-1")!!.stagingPath)
    }

    @Test
    fun `finishing the same request twice gives one book and one provenance row`() = runTest {
        // Given: a finished request whose last write is lost, so the next start finishes it again
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        runCurrent()
        val finished = harness.row("book-1")!!
        val provenance = harness.sources.peek()
        harness.world.databaseLocked = true
        harness.database.update(finished.copy(state = CatalogueAcquisitionEntity.STATE_ADDING, completedAt = null))
        harness.world.databaseLocked = false

        // When
        harness.restart().restoreAfterRestart()
        runCurrent()
        harness.restart().restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertEquals(provenance, harness.sources.peek())
        assertEquals(setOf("lib-book-1"), harness.world.libraryBooks.getValue("p1"))
        assertEquals(1, harness.adder.calls.size)
    }

    @Test
    fun `asking for a book that is in the library answers with it and starts nothing`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        runCurrent()
        val rows = harness.database.peek("p1")

        // When: the same file, another file of the same book, and the listing entry it came from
        val sameFile = harness.queue.request(bookRequest(1))
        val otherFile = harness.queue.request(bookRequest(1, representationKey = "application/epub+zip#2"))
        val fromListing = harness.queue.request(bookRequest(9).copy(publicationKey = "urn:entry:1", detailIdentity = null))
        val byDetail = harness.queue.request(bookRequest(9).copy(detailIdentity = "book-1"))
        runCurrent()

        // Then
        listOf(sameFile, otherFile, fromListing, byDetail).forEach { outcome ->
            assertEquals(CatalogueRequestOutcome.InLibrary("lib-book-1"), outcome)
        }
        assertEquals(rows, harness.database.peek("p1"))
        assertEquals(1, harness.source.calls.size)
    }

    @Test
    fun `the same book in another catalogue is a download of its own`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        runCurrent()

        // When
        val elsewhere = harness.queue.request(bookRequest(1, sourceId = "source-2"))
        runCurrent()

        // Then
        assertIs<CatalogueRequestOutcome.Queued>(elsewhere)
        assertEquals(2, harness.source.calls.size)
    }

    @Test
    fun `a whole page is looked up at once and a removed book is not in the library`() = runTest {
        // Given: books 1 and 2 acquired, book 2 removed from the library since
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        harness.queue.request(bookRequest(2))
        runCurrent()
        harness.world.libraryBooks.getValue("p1").remove("lib-book-2")
        val lookup = DatabaseCatalogueLibraryLookup(harness.session, harness.sources) { harness.world.activeProfile }
        val acquired = entry("book-1")
        val removed = entry("book-2")
        val listingOfAcquired = entry("urn:entry:1")
        val editionListedElsewhere = entry("some-other-key", detailIdentity = "book-1")
        val never = entry("book-3")

        // When
        val page = lookup.libraryBooksFor("source-1", listOf(acquired, removed, listingOfAcquired, editionListedElsewhere, never))

        // Then
        assertEquals(
            mapOf(acquired to "lib-book-1", listingOfAcquired to "lib-book-1", editionListedElsewhere to "lib-book-1"),
            page,
        )
        assertEquals("lib-book-1", lookup.libraryBookFor("source-1", acquired))
        assertNull(lookup.libraryBookFor("source-1", removed))
        assertNull(lookup.libraryBookFor("source-2", acquired))
        assertTrue(lookup.libraryBooksFor("source-1", emptyList()).isEmpty())
    }

    @Test
    fun `with no profile open nothing is in the library`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        runCurrent()
        val lookup = DatabaseCatalogueLibraryLookup(harness.session, harness.sources) { harness.world.activeProfile }

        // When
        harness.world.activeProfile = null

        // Then
        assertNull(lookup.libraryBookFor("source-1", entry("book-1")))
    }

    @Test
    fun `closing the profile while a book is added finishes the request when the book made it`() = runTest {
        // Given: the import committed, and the profile is closed before the queue heard of it
        val harness = QueueHarness(backgroundScope)
        harness.adder.dieAfterAdding = true
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Adding, harness.state("book-1"))

        // When
        harness.switchProfile("p2")
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertEquals(1, harness.sources.peek("p1").size)
        assertTrue(harness.database.peek("p2").isEmpty())
        assertTrue(harness.sources.peek("p2").isEmpty())
    }

    @Test
    fun `a row in a state this version does not know becomes interrupted`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        harness.queue.request(bookRequest(1))
        harness.queue.request(bookRequest(2))
        harness.queue.request(bookRequest(3))
        runCurrent()
        harness.world.databaseLocked = true
        harness.database.update(harness.row("book-3")!!.copy(state = "transcoding", stagingPath = "/staging/p1/left.epub.part"))
        harness.database.update(harness.row("book-2")!!.copy(state = "failed", failureReason = "reason-from-the-future"))
        harness.world.databaseLocked = false
        assertNull(harness.row("book-2")!!.toAcquisition(), "such a row cannot be shown")
        assertNull(harness.row("book-3")!!.toAcquisition())

        // When
        val restarted = harness.restart()
        restarted.restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(mapOf("book-1" to Interrupted, "book-2" to Interrupted, "book-3" to Interrupted), harness.states())
        assertEquals(3, restarted.observeAcquisitions().first().size, "none is hidden any more")
        assertNull(harness.row("book-2")!!.failureReason)
        assertNull(harness.row("book-3")!!.stagingPath)
        assertTrue(restarted.cancel(harness.row("book-3")!!.requestId))
    }

    @Test
    fun `staged files no request refers to are deleted at start and the others stay`() = runTest {
        // Given: a file of a running download, and two nobody owns
        val harness = QueueHarness(backgroundScope)
        harness.files.files["/staging/p1/orphan.epub.part"] = FakeStagingFiles.Stored(10)
        harness.files.files["/staging/p1/orphan-checked.epub"] = FakeStagingFiles.Stored(10)
        harness.files.files["/staging/p2/other-profile.epub.part"] = FakeStagingFiles.Stored(10)

        // When
        harness.queue.restoreAfterRestart()
        harness.source.hold("book-1")
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        val running = assertNotNull(harness.row("book-1")!!.stagingPath)
        assertEquals(setOf(running, "/staging/p2/other-profile.epub.part"), harness.files.files.keys)
    }

    @Test
    fun `a file over the limit records the size the catalogue declared`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        val declared = CatalogueAcquisitionLimits.MAX_FILE_BYTES + 1
        harness.source.failNext("book-1", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.TooLarge, declaredLength = declared))

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then: enough for "Too large to add · <size> (the limit is <limit>)"
        val listed = harness.queue.observeAcquisitions().first().single()
        assertEquals(Failed(AcquisitionFailureReason.TooLarge), listed.state)
        assertEquals(declared, listed.expectedSizeBytes)
        assertEquals(512L * 1024 * 1024, CatalogueAcquisitionLimits.MAX_FILE_BYTES)
    }

    @Test
    fun `a file over the limit with no declared size keeps what the catalogue listed`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.failNext("book-1", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.TooLarge))

        // When
        harness.queue.request(bookRequest(1).copy(expectedSizeBytes = 700L * 1024 * 1024))
        runCurrent()

        // Then
        assertEquals(700L * 1024 * 1024, harness.row("book-1")!!.expectedSizeBytes)
    }

    @Test
    fun `not enough space records how much was needed when the size is known`() = runTest {
        // Given: 3000 bytes declared, less than that plus the margin free
        val harness = QueueHarness(backgroundScope)
        harness.files.freeSpace = AcquisitionWorker.MIN_FREE_BYTES + 100

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        val row = harness.row("book-1")!!
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertEquals(3000L + AcquisitionWorker.MIN_FREE_BYTES, row.neededBytes)
        assertEquals(3000L, row.expectedSizeBytes)
    }

    @Test
    fun `not enough space with an unknown size records no needed amount`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.undeclared += "book-1"
        harness.source.chunks["book-1"] = listOf(AcquisitionWorker.SPACE_CHECK_INTERVAL_BYTES.toInt(), 10)
        harness.files.freeSpaceAfterWrites = 1000L to 0L

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertNull(harness.row("book-1")!!.neededBytes)
    }

    @Test
    fun `a retried request forgets the amount its last failure needed`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.files.freeSpace = AcquisitionWorker.MIN_FREE_BYTES + 100
        val failed = harness.queue.requestQueued(bookRequest(1))
        runCurrent()
        assertNotNull(harness.row("book-1")!!.neededBytes)
        harness.files.freeSpace = 10L * 1024 * 1024 * 1024

        // When
        harness.queue.retry(failed.requestId)
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertNull(harness.row("book-1")!!.neededBytes)
    }

    @Test
    fun `the origin is scheme host and port and nothing else`() {
        assertEquals("https://books.example:443", originOf("https://Books.Example/opds/KEY123/new?token=abc#top"))
        assertEquals("http://192.168.1.10:8080", originOf("http://192.168.1.10:8080/opds"))
        assertEquals("http://books.example:80", originOf("http://user:secret@books.example"))
        assertEquals("https://[::1]:8443", originOf("https://[::1]:8443/opds"))
        assertEquals("https://[::1]:443", originOf("https://[::1]/opds"))
        assertNull(originOf("local://"))
        assertNull(originOf("not an address"))
        assertNull(originOf("https://"))
        assertNull(originOf(null))
    }

    private fun entry(publicationKey: String, detailIdentity: String? = null) =
        com.retro99.catalogue.domain.CatalogueEntryIdentity(publicationKey, detailIdentity)
}
