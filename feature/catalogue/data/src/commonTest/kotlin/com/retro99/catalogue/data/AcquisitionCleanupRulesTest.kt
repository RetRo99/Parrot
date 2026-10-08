package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Failed
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is cleaned up, and what is kept, when a request, a catalogue or a file goes away. */
@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionCleanupRulesTest {

    @Test
    fun `a cancel that lands while the library commits the book still records where it came from`() = runTest {
        landsWhileCommitting { harness, requestId -> harness.queue.cancel(requestId) }
    }

    @Test
    fun `removing the catalogue while the library commits the book still records where it came from`() = runTest {
        landsWhileCommitting { harness, _ ->
            harness.sourceAddresses.clear()
            harness.queue.forget("p1", "source-1")
        }
    }

    /** The request's row is deleted in the instant between the library's commit and the queue hearing of it. */
    private suspend fun TestScope.landsWhileCommitting(change: suspend (QueueHarness, String) -> Unit) {
        // Given
        val harness = QueueHarness(backgroundScope)
        val rowDeleted = CompletableDeferred<Unit>()
        harness.database.afterDelete = { rowDeleted.complete(Unit) }
        var requestId = ""
        harness.adder.whileCommitting = {
            backgroundScope.launch { change(harness, requestId) }
            rowDeleted.await()
        }

        // When
        requestId = harness.queue.requestQueued(bookRequest(1)).requestId
        runCurrent()

        // Then the book is in the library and is known to have come from this publication
        assertEquals(emptyList(), harness.database.peek("p1"))
        val provenance = harness.sources.peek().single()
        assertEquals("lib-book-1", provenance.libraryBookId)
        assertEquals(requestId, provenance.id)
        assertEquals("source-1", provenance.sourceId)
        assertEquals("book-1", provenance.publicationKey)
        assertEquals("urn:entry:1", provenance.detailIdentity)
        assertEquals("sha-3000-3000", provenance.contentHash)
        assertEquals("https://books.example:443", provenance.catalogueOrigin)
        assertTrue(harness.files.files.isEmpty())

        // And asking for it again does not download it a second time
        assertEquals(CatalogueRequestOutcome.InLibrary("lib-book-1"), harness.queue.request(bookRequest(1)))
        assertEquals(1, harness.source.calls.size)
        assertTrue(harness.world.lockViolations.isEmpty())
    }

    @Test
    fun `new account details keep a request that waits for sign-in, and removing the catalogue or its account does not`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.failNext("book-1", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.SignInNeeded))
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Failed(AcquisitionFailureReason.SignIn), harness.state("book-1"))

        // When
        harness.queue.cancel("p1", "source-1")

        // Then
        assertEquals(Failed(AcquisitionFailureReason.SignIn), harness.state("book-1"))

        // When
        harness.queue.forget("p1", "source-1")

        // Then
        assertNull(harness.row("book-1"))
        assertEquals(emptyList(), harness.queue.observeAcquisitions().first())
    }

    @Test
    fun `a book whose file was removed is downloaded again, goes back to the same book and gets no second record`() = runTest {
        // Given a finished download whose file is no longer on this device
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Done, harness.state("book-1"))
        harness.world.libraryBooks.getValue("p1").remove("lib-book-1")

        // When the same bytes are downloaded again
        val again = harness.queue.request(bookRequest(1))
        runCurrent()

        // Then it was a real download, into the same book
        assertIs<CatalogueRequestOutcome.Queued>(again)
        assertEquals(2, harness.source.calls.size)
        assertEquals(listOf("lib-book-1", "lib-book-1"), harness.database.peek("p1").map { it.libraryBookId })
        assertEquals(listOf(Done, Done), harness.database.peek("p1").map { it.acquisitionState() })

        // And the book has one record of where it came from
        assertEquals(1, harness.sources.peek().size)
        assertEquals(CatalogueRequestOutcome.InLibrary("lib-book-1"), harness.queue.request(bookRequest(1)))
    }

    @Test
    fun `other bytes for a book whose file was removed are recorded as a separate acquisition`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        runCurrent()
        harness.world.libraryBooks.getValue("p1").remove("lib-book-1")
        // The library decides: different bytes are a separate book, never an overwrite.
        harness.adder.result = { CatalogueBookAddResult.Added("lib-book-1-new-file") }
        harness.source.chunks["book-1"] = listOf(4000)

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(listOf("lib-book-1", "lib-book-1-new-file"), harness.sources.peek().map { it.libraryBookId })
        assertEquals(2, harness.sources.peek().map { it.contentHash }.distinct().size)
    }
}
