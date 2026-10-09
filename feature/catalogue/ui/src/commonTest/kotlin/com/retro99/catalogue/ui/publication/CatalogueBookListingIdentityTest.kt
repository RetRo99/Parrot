package com.retro99.catalogue.ui.publication

import com.retro99.catalogue.ui.browse.*
import com.retro99.catalogue.ui.downloads.TestDownloadQueue
import com.retro99.catalogue.ui.navigation.CatalogueBookPlace
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogueBookListingIdentityTest {
    @Test fun editionsFallbackKeepsOriginalListingIdentityInProvenanceAndLibraryLookup() = runTest {
        val editions = listOf(book("Title", id = "edition1"), book("Title", id = "edition2"))
        val gateway = FakeGateway(); val queue = TestDownloadQueue(); val library = FakeLibrary()
        val page = CatalogueBookPage(SOURCE, CatalogueBookPlace(FakeTarget("details"), editions, "listing-id"), gateway, library, queue, backgroundScope)
        runCurrent(); gateway.repository.answer("details", feed("details", books = editions)); runCurrent()
        assertTrue(library.asked.flatten().contains("listing-id"))
        page.download(); runCurrent()
        assertEquals("listing-id", queue.requests.single().detailIdentity)
        library.inLibrary = setOf("listing-id")
        queue.rows.value = emptyList(); runCurrent()
        assertIs<BookMainAction.Done>(page.state.value.action)
    }
}
