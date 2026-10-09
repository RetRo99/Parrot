package com.retro99.catalogue.data

import com.retro99.catalogue.domain.CatalogueEntryIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogueLibraryDetailsTest {
    @Test fun acquisition_date_survives_purging_downloads_and_a_removed_library_book_is_not_done() = runTest {
        val h = QueueHarness(backgroundScope)
        h.queue.request(bookRequest(1)); runCurrent()
        val provenance = h.sources.peek().single()
        h.queue.purgeFinished()
        val lookup = DatabaseCatalogueLibraryLookup(h.session, h.sources) { h.world.activeProfile }
        val entry = CatalogueEntryIdentity("book-1")
        val details = lookup.libraryDetailsFor("source-1", listOf(entry)).getValue(entry)
        assertEquals(provenance.libraryBookId, details.libraryBookId)
        assertEquals(provenance.acquiredAt, details.acquiredAt)
        h.world.libraryBooks.getValue("p1").clear()
        assertTrue(lookup.libraryDetailsFor("source-1", listOf(entry)).isEmpty())
    }
}
