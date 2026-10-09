package com.retro99.catalogue.ui.browse

import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.downloads.*
import com.retro99.catalogue.ui.navigation.CataloguePlace
import com.retro99.server.api.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogueRowDownloadsTest {
    private class Harness(scope: TestScope, flagged: Boolean = false, val books: List<CataloguePublication> = listOf(book("One"), book("Untapped"))) {
        val gateway = FakeGateway().also { it.source.value = it.source.value!!.copy(listEntriesAreBooks = flagged) }
        val queue = TestDownloadQueue()
        val library = FakeLibrary()
        val browser = CatalogueBrowser(SOURCE, CataloguePlace(FakeTarget("list"), "List", false, null), gateway, library, scope.backgroundScope, queue = queue)
        val rows get() = (browser.state.value.content as CatalogueBrowseContent.Loaded).books
        suspend fun loaded(scope: TestScope) {
            scope.runCurrent(); gateway.repository.answer("list", feed("list", books = books, next = "next")); scope.runCurrent()
        }
    }

    @Test fun onlyTappedBookLoadsDetailsThenFirstOpenableFileStartsAndNoticeAppears() = runTest {
        val h = Harness(this); h.loaded(this)
        h.browser.downloadBook(h.rows.first().key)
        assertEquals(ListDownloadState.GettingReady, h.rows.first().download)
        runCurrent(); assertEquals(listOf("list", "list"), h.gateway.repository.requested)
        val p = h.books.first(); val unsupported = p.acquisitionChoices.first().copy(isOpenable = false)
        val full = p.copy(acquisitionChoices = listOf(unsupported) + book("One", files = 2).acquisitionChoices)
        h.gateway.repository.answer("list", feed("list", books = listOf(full, h.books.last()))); runCurrent()
        assertEquals(1, h.queue.requests.size)
        assertEquals("id:One", h.queue.requests.single().publicationKey)
        assertEquals("One", h.browser.state.value.downloadNotice)
        assertEquals(ListDownloadState.Waiting, h.rows.first().download)
        assertEquals(ListDownloadState.Available, h.rows.last().download)
    }

    @Test fun gettingReadyCancelDropsEvenNonCooperativeLateAnswerAndDoesNotQueue() = runTest {
        val h = Harness(this); h.loaded(this)
        val key = h.rows.first().key
        h.browser.downloadBook(key); runCurrent(); h.browser.cancelDownload(key)
        assertEquals(ListDownloadState.Available, h.rows.first().download)
        h.gateway.repository.answer("list", feed("list", books = h.books)); runCurrent()
        assertTrue(h.queue.requests.isEmpty()); assertNull(h.browser.state.value.downloadNotice)
    }

    @Test fun unsupportedFallsBackSilentlyAndNormalTapStillOpensBook() = runTest {
        val h = Harness(this); h.loaded(this)
        val key = h.rows.first().key
        h.browser.downloadBook(key); runCurrent()
        h.gateway.repository.answer("list", feed("list", books = listOf(book("One", files = 0)))); runCurrent()
        assertIs<CatalogueBrowseNavigation.OpenBook>(h.browser.state.value.navigation)
        assertEquals(ListDownloadState.Available, h.rows.first().download)
        assertTrue(h.queue.requests.isEmpty()); assertNull(h.browser.state.value.downloadNotice)
        h.browser.navigationHandled(); h.browser.openBook(key)
        assertIs<CatalogueBrowseNavigation.OpenBook>(h.browser.state.value.navigation)
    }

    @Test fun queueUpdatesEveryStateAndCancelNeverTouchesCheckingAddingOrDone() = runTest {
        val h = Harness(this); h.loaded(this)
        val key = h.rows.first().key
        val expected = listOf(
            AcquisitionState.Waiting to ListDownloadState.Waiting,
            AcquisitionState.Downloading to ListDownloadState.Downloading(500_000, 1_200_000),
            AcquisitionState.Checking to ListDownloadState.Adding,
            AcquisitionState.Adding to ListDownloadState.Adding,
            AcquisitionState.Interrupted to ListDownloadState.Available,
        ) + AcquisitionFailureReason.entries.map { AcquisitionState.Failed(it) to ListDownloadState.Available }
        for ((state, presentation) in expected) {
            h.queue.rows.value = listOf(downloadFixture(state).copy(sourceId = SOURCE, publicationKey = "id:One")); runCurrent()
            assertEquals(presentation, h.rows.first().download)
            if (state == AcquisitionState.Waiting || state == AcquisitionState.Downloading) {
                h.browser.cancelDownload(key); runCurrent(); assertEquals(ListDownloadState.Available, h.rows.first().download)
            } else {
                val before = h.queue.actions.size; h.browser.cancelDownload(key); runCurrent(); assertEquals(before, h.queue.actions.size)
            }
        }
        h.queue.rows.value = listOf(downloadFixture(AcquisitionState.Downloading).copy(sourceId = SOURCE, publicationKey = "id:One", expectedSizeBytes = null)); runCurrent()
        assertEquals(ListDownloadState.Downloading(500_000, null), h.rows.first().download)
        h.library.inLibrary = setOf("id:One")
        h.queue.rows.value = listOf(downloadFixture(AcquisitionState.Done).copy(sourceId = SOURCE, publicationKey = "id:One")); runCurrent()
        assertEquals(ListDownloadState.InLibrary, h.rows.first().download)
        h.queue.rows.value = emptyList(); runCurrent(); assertEquals(ListDownloadState.InLibrary, h.rows.first().download)
    }

    @Test fun linkedEntryWithSeveralEditionsOpensBookPageAndPreservesListingIdentityWhenOneFileStarts() = runTest {
        for (count in 1..2) {
            val entry = folder("One", "details", subtitle = "Author")
            val gateway = FakeGateway().also { it.source.value = it.source.value!!.copy(listEntriesAreBooks = true) }
            val queue = TestDownloadQueue()
            val browser = CatalogueBrowser(SOURCE, CataloguePlace(FakeTarget("list"), "List", false, null), gateway, FakeLibrary(), backgroundScope, queue = queue)
            runCurrent(); gateway.repository.answer("list", feed("list", folders = listOf(entry), next = "next")); runCurrent()
            val key = (browser.state.value.content as CatalogueBrowseContent.Loaded).books.single().key
            browser.downloadBook(key); runCurrent()
            assertEquals(listOf("list", "details"), gateway.repository.requested)
            gateway.repository.answer("details", feed("details", books = (1..count).map { book("One", id = "edition$it") })); runCurrent()
            if (count == 2) {
                val navigation = assertIs<CatalogueBrowseNavigation.OpenBook>(browser.state.value.navigation)
                assertEquals(2, navigation.book.publications.size); assertTrue(queue.requests.isEmpty())
                assertEquals("nav:One", navigation.book.listingIdentity)
            } else {
                assertEquals("nav:One", queue.requests.single().detailIdentity)
                assertEquals("edition1", queue.requests.single().publicationKey)
            }
        }
    }

    @Test fun partialEntryLoadsOnlyItsFullEntryAndDuplicateTapDoesNotFetchTwice() = runTest {
        val partial = book("One").copy(links = listOf(link("full", relations = listOf("alternate")).copy(mediaType = CatalogueMediaType("application", "atom+xml", mapOf("type" to "entry")))))
        val h = Harness(this, books = listOf(partial, book("Untapped"))); h.loaded(this)
        val key = h.rows.first().key
        h.browser.downloadBook(key); h.browser.downloadBook(key); runCurrent()
        assertEquals(listOf("list", "full"), h.gateway.repository.requested)
        h.gateway.repository.answer("full", bookDocument(partial, "full")); runCurrent()
        assertEquals("$ORIGIN/full", h.queue.requests.single().listingUrl)
    }

    @Test fun profileChangeDropsLateRowDetailsWithoutStartingDownload() = runTest {
        val h = Harness(this); h.loaded(this)
        h.browser.downloadBook(h.rows.first().key); runCurrent()
        h.gateway.source.value = h.gateway.source.value!!.copy(profileId = "other"); runCurrent()
        h.gateway.repository.answer("list", feed("list", books = h.books)); runCurrent()
        assertTrue(h.browser.state.value.closed); assertTrue(h.queue.requests.isEmpty())
    }

    @Test fun failedRequestOpensBookPageForItsRepairActionInsteadOfPretendingItStarted() = runTest {
        val h = Harness(this); h.loaded(this)
        h.queue.rows.value = listOf(downloadFixture(AcquisitionState.Failed(AcquisitionFailureReason.SignIn)).copy(sourceId = SOURCE, publicationKey = "id:One")); runCurrent()
        h.browser.downloadBook(h.rows.first().key); runCurrent()
        h.gateway.repository.answer("list", feed("list", books = h.books)); runCurrent()
        assertIs<CatalogueBrowseNavigation.OpenBook>(h.browser.state.value.navigation)
        assertTrue(h.queue.requests.isEmpty()); assertNull(h.browser.state.value.downloadNotice)
    }
}
