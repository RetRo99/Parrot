package com.retro99.catalogue.ui.browse

import com.retro99.catalogue.ui.navigation.CataloguePlace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A catalogue whose "next" links never end must not keep the browser asking. */
@OptIn(ExperimentalCoroutinesApi::class)
class CataloguePagingLoopTest {
    private class Harness(scope: TestScope, maxPages: Int = CatalogueBrowser.MAX_LOADED_PAGES) {
        val gateway = FakeGateway()
        val repo = gateway.repository
        val browser = CatalogueBrowser(SOURCE, CataloguePlace(FakeTarget("p1"), null, false), gateway, FakeLibrary(), scope.backgroundScope, maxPages)
        val loaded get() = assertIs<CatalogueBrowseContent.Loaded>(browser.state.value.content)
        val titles get() = loaded.books.map { it.title }

        /** Scrolls to the end and taps "Load more" as often as a user plausibly could. */
        fun TestScope.keepAsking(times: Int = 50) = repeat(times) {
            browser.onNearEnd()
            browser.loadMore()
            runCurrent()
        }
    }

    private fun books(vararg titles: String) = titles.map { book(it) }

    @Test fun a_next_link_that_points_at_its_own_page_ends_the_list() = runTest {
        val h = Harness(this)
        runCurrent()

        h.repo.answer("p1", feed("p1", books = books("A", "B"), next = "p1"))
        runCurrent()

        assertEquals(CataloguePaging.None, h.loaded.more)
        with(h) { keepAsking() }
        assertEquals(listOf("p1"), h.repo.requested)
        assertEquals(listOf("A", "B"), h.titles)
    }

    @Test fun a_next_link_back_to_an_earlier_page_ends_the_list_after_every_page_was_shown_once() = runTest {
        val h = Harness(this)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A"), next = "p2"))
        runCurrent()
        h.browser.onNearEnd()
        runCurrent()
        h.repo.answer("p2", feed("p2", books = books("B"), next = "p3"))
        runCurrent()
        h.browser.onNearEnd()
        runCurrent()

        h.repo.answer("p3", feed("p3", books = books("C"), next = "p1"))
        runCurrent()

        assertEquals(CataloguePaging.None, h.loaded.more)
        with(h) { keepAsking() }
        assertEquals(listOf("p1", "p2", "p3"), h.repo.requested)
        assertEquals(listOf("A", "B", "C"), h.titles)
    }

    @Test fun a_page_that_answers_from_the_address_of_an_earlier_page_is_where_the_list_ends() = runTest {
        // The link looks new each time; the catalogue redirects it to the page before.
        val h = Harness(this)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A"), next = "again-1"))
        runCurrent()
        h.browser.onNearEnd()
        runCurrent()
        h.repo.answer("again-1", feed("again-1", books = books("A"), next = "p1", responseUrl = "$ORIGIN/p1"))
        runCurrent()

        assertEquals(CataloguePaging.None, h.loaded.more)
        with(h) { keepAsking() }
        assertEquals(listOf("p1", "again-1"), h.repo.requested)
    }

    @Test fun a_loop_is_still_found_after_the_first_pages_were_dropped_at_the_page_limit() = runTest {
        val h = Harness(this, maxPages = 2)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A"), next = "p2"))
        runCurrent()
        for ((page, next) in listOf("p2" to "p3", "p3" to "p4", "p4" to "p1")) {
            h.browser.onNearEnd()
            runCurrent()
            h.repo.answer(page, feed(page, books = books(page), next = next))
            runCurrent()
        }

        // p1 and p2 are no longer loaded, and p4 still may not lead back to p1.
        assertEquals(listOf("p3", "p4"), h.titles)
        assertEquals(CataloguePaging.None, h.loaded.more)
        with(h) { keepAsking() }
        assertEquals(listOf("p1", "p2", "p3", "p4"), h.repo.requested)
    }

    @Test fun pages_brought_back_from_the_start_can_be_followed_forward_again() = runTest {
        // Dropping the far end and walking forward again is not a loop.
        val h = Harness(this, maxPages = 2)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A"), next = "p2"))
        runCurrent()
        for (page in listOf("p2", "p3")) {
            h.browser.onNearEnd()
            runCurrent()
            h.repo.answer(page, feed(page, books = books(page), next = if (page == "p2") "p3" else null))
            runCurrent()
        }
        h.browser.onNearStart()
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A"), next = "p2"))
        runCurrent()
        assertEquals(listOf("A", "p2"), h.titles)
        assertEquals(CataloguePaging.Auto, h.loaded.more)

        h.browser.onNearEnd()
        runCurrent()
        h.repo.answer("p3", feed("p3", books = books("p3")))
        runCurrent()

        assertEquals(listOf("p2", "p3"), h.titles)
        assertEquals(CataloguePaging.None, h.loaded.more)
    }

    @Test fun endless_new_pages_without_books_stop_loading_by_themselves_and_wait_for_a_tap() = runTest {
        val h = Harness(this)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A"), next = "e1"))
        runCurrent()
        var page = 1
        repeat(CatalogueBrowser.MAX_EMPTY_PAGES_IN_A_ROW) {
            h.browser.onNearEnd()
            runCurrent()
            h.repo.answer("e$page", feed("e$page", next = "e${page + 1}"))
            runCurrent()
            page++
        }

        // Scrolling asks for nothing more, however often the list reports its end.
        assertEquals(CataloguePaging.Button, h.loaded.more)
        repeat(50) { h.browser.onNearEnd() }
        runCurrent()
        assertEquals(1 + CatalogueBrowser.MAX_EMPTY_PAGES_IN_A_ROW, h.repo.requested.size)

        // A tap asks for exactly one, and a page with books lets the list load by itself again.
        h.browser.loadMore()
        runCurrent()
        assertEquals(2 + CatalogueBrowser.MAX_EMPTY_PAGES_IN_A_ROW, h.repo.requested.size)
        h.repo.answer("e$page", feed("e$page", books = books("B"), next = "more"))
        runCurrent()
        assertEquals(listOf("A", "B"), h.titles)
        assertEquals(CataloguePaging.Auto, h.loaded.more)
    }
}
