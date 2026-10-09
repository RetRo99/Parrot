package com.retro99.catalogue.ui.browse

import com.retro99.catalogue.ui.navigation.CataloguePlace
import com.retro99.server.api.CatalogueErrorKind
import com.retro99.server.api.CatalogueFacetGroup
import com.retro99.server.api.CatalogueGroup
import com.retro99.server.api.OpdsAccountDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueBrowserTest {
    private class Harness(
        scope: TestScope,
        start: CataloguePlace? = null,
        val library: FakeLibrary = FakeLibrary(),
        maxPages: Int = CatalogueBrowser.MAX_LOADED_PAGES,
    ) {
        val gateway = FakeGateway()
        val repo = gateway.repository
        val browser = CatalogueBrowser(SOURCE, start, gateway, library, scope.backgroundScope, maxPages)
        val state get() = browser.state.value
        val loaded get() = assertIs<CatalogueBrowseContent.Loaded>(state.content)
        val titles get() = loaded.books.map { it.title }
    }

    private fun place(name: String, title: String? = null, fromEntry: Boolean = false) = CataloguePlace(FakeTarget(name), title, fromEntry)
    private fun books(vararg titles: String) = titles.map { book(it) }

    // --- states -------------------------------------------------------------------------

    @Test fun first_load_shows_the_catalogue_name_and_then_what_the_page_contains() = runTest {
        val h = Harness(this)
        runCurrent()
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        assertEquals("Project Gutenberg", h.state.catalogueName)
        assertNull(h.state.title)
        assertEquals(listOf("root"), h.repo.requested)

        h.repo.answer(
            "root",
            feed(
                search = true,
                folders = listOf(folder("Latest additions"), folder("By subject", subtitle = "Adventure, History, Poetry…")),
                groups = listOf(CatalogueGroup(text("Popular this week"), listOf(link("popular")), books("Frankenstein", "Moby Dick"), emptyList())),
            ),
        )
        runCurrent()
        assertTrue(h.state.searchAvailable)
        assertEquals(listOf("Popular this week"), h.loaded.shelves.map { it.title })
        assertTrue(h.loaded.shelves.single().hasSeeAll)
        assertEquals(listOf("Frankenstein", "Moby Dick"), h.loaded.shelves.single().books.map { it.title })
        assertEquals(listOf("Latest additions" to null, "By subject" to "Adventure, History, Poetry…"), h.loaded.folders.map { it.title to it.subtitle })
        assertNull(h.state.title)
        assertEquals(CataloguePaging.None, h.loaded.more)
    }

    @Test fun the_plainest_catalogue_is_folders_only_with_no_search() = runTest {
        val h = Harness(this)
        runCurrent()
        h.repo.answer("root", feed(folders = listOf(folder("Fiction"), folder("All books"))))
        runCurrent()
        assertFalse(h.state.searchAvailable)
        assertTrue(h.loaded.shelves.isEmpty() && h.loaded.chips.isEmpty() && h.loaded.books.isEmpty())
        assertEquals(listOf("Fiction", "All books"), h.loaded.folders.map { it.title })
    }

    @Test fun an_inner_page_is_titled_by_what_was_tapped_or_else_by_the_page() = runTest {
        val tapped = Harness(this, place("popular", title = "Popular"))
        val untitled = Harness(this, place("popular"))
        runCurrent()
        assertEquals("Popular", tapped.state.title)
        tapped.repo.answer("popular", feed("popular", title = "All Books", books = books("A", "B")))
        untitled.repo.answer("popular", feed("popular", title = "All Books", books = books("A", "B")))
        runCurrent()
        assertEquals("Popular", tapped.state.title)
        assertEquals("All Books", untitled.state.title)
    }

    @Test fun an_empty_page_is_an_empty_folder_and_not_an_error() = runTest {
        val h = Harness(this, place("poetry", "Poetry"))
        runCurrent()
        h.repo.answer("poetry", feed("poetry"))
        runCurrent()
        assertEquals(CatalogueBrowseContent.EmptyFolder, h.state.content)
    }

    @Test fun each_way_a_page_fails_has_its_own_state_and_buttons() = runTest {
        val expected = mapOf(
            CatalogueErrorKind.Timeout to CatalogueBrowseContent.Failed(CataloguePageFailure.TimedOut),
            CatalogueErrorKind.ServerError to CatalogueBrowseContent.Failed(CataloguePageFailure.CatalogueError),
            CatalogueErrorKind.Forbidden to CatalogueBrowseContent.Failed(CataloguePageFailure.NotAllowed),
            CatalogueErrorKind.NotFound to CatalogueBrowseContent.Failed(CataloguePageFailure.NotFound),
            CatalogueErrorKind.TooLarge to CatalogueBrowseContent.Failed(CataloguePageFailure.TooLarge),
            CatalogueErrorKind.InvalidDocument to CatalogueBrowseContent.Failed(CataloguePageFailure.NotACatalogue),
            CatalogueErrorKind.Tls to CatalogueBrowseContent.Failed(CataloguePageFailure.Certificate),
            CatalogueErrorKind.RateLimited to CatalogueBrowseContent.RateLimited,
            CatalogueErrorKind.OfflineNoSavedCopy to CatalogueBrowseContent.OfflineNone,
            CatalogueErrorKind.Unreachable to CatalogueBrowseContent.OfflineNone,
        )
        expected.forEach { (kind, content) ->
            val h = Harness(this)
            runCurrent()
            h.repo.answer("root", failure(kind))
            runCurrent()
            assertEquals(content, h.state.content, kind.name)
            assertNull(h.state.signIn)
        }
        assertEquals(listOf(CataloguePageFailure.TimedOut, CataloguePageFailure.CatalogueError), CataloguePageFailure.entries.filter { it.canRetry })
        assertEquals(listOf(CataloguePageFailure.NotACatalogue, CataloguePageFailure.Certificate), CataloguePageFailure.entries.filter { it.offersSettings })
    }

    @Test fun try_again_asks_for_the_same_page_and_shows_the_first_load_meanwhile() = runTest {
        val h = Harness(this, place("subject"))
        runCurrent()
        h.repo.answer("subject", failure(CatalogueErrorKind.OfflineNoSavedCopy))
        runCurrent()
        h.browser.retry()
        runCurrent()
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        assertEquals(listOf("subject", "subject"), h.repo.requested)
        h.repo.answer("subject", feed("subject", books = books("A", "B")))
        runCurrent()
        assertEquals(listOf("A", "B"), h.titles)
    }

    @Test fun a_saved_copy_is_shown_with_the_time_it_was_saved() = runTest {
        val h = Harness(this, place("popular"))
        runCurrent()
        h.repo.answer("popular", feed("popular", books = books("A", "B"), savedCopyAt = 1_234L))
        runCurrent()
        assertEquals(1_234L, h.loaded.savedCopyAt)
        assertEquals(listOf("A", "B"), h.titles)
    }

    // --- book rows ----------------------------------------------------------------------

    @Test fun rows_carry_author_cover_and_library_state_looked_up_once_for_the_page() = runTest {
        val h = Harness(this, place("popular"), library = FakeLibrary(inLibrary = setOf("id:Treasure Island")))
        runCurrent()
        h.repo.answer(
            "popular",
            feed("popular", books = listOf(book("Treasure Island", "Robert Louis Stevenson", cover = "$ORIGIN/c.png"), book("A Very Long Title", author = null))),
        )
        runCurrent()
        val (first, second) = h.loaded.books
        assertEquals("Robert Louis Stevenson", first.author)
        assertTrue(first.inLibrary)
        assertEquals(SOURCE, first.cover?.sourceId)
        assertEquals("$ORIGIN/c.png", first.cover?.url)
        assertNull(second.author)
        assertNull(second.cover)
        assertFalse(second.inLibrary)
        assertEquals(listOf(listOf("id:Treasure Island", "id:A Very Long Title")), h.library.asked)
    }

    @Test fun a_failing_library_lookup_does_not_hide_the_page() = runTest {
        val h = Harness(this, place("popular"), library = FakeLibrary(fails = true))
        runCurrent()
        h.repo.answer("popular", feed("popular", books = books("A", "B")))
        runCurrent()
        assertEquals(listOf(false, false), h.loaded.books.map { it.inLibrary })
    }

    @Test fun only_rows_with_a_same_title_sibling_get_the_telling_line() = runTest {
        val h = Harness(this, place("results"))
        runCurrent()
        h.repo.answer(
            "results",
            feed(
                "results",
                books = listOf(
                    book("Treasure Island", id = "1", edition = "Illustrated edition", year = "1911", files = 2),
                    book("Middlemarch", id = "2"),
                    book("treasure  island", id = "3", year = "2004"),
                    book("Treasure Island", author = "Somebody Else", id = "4"),
                ),
            ),
        )
        runCurrent()
        assertEquals(
            listOf(CatalogueTellingLine("Illustrated edition", "1911", 2), null, CatalogueTellingLine(null, "2004", 1), null),
            h.loaded.books.map { it.telling },
        )
        assertNull(h.loaded.sameBookCount)
    }

    // --- opening an entry ---------------------------------------------------------------

    @Test fun tapping_a_row_a_folder_and_see_all_name_where_to_go() = runTest {
        val h = Harness(this)
        runCurrent()
        val moby = book("Moby Dick")
        h.repo.answer(
            "root",
            feed(
                books = listOf(moby),
                folders = listOf(folder("By subject", target = "subjects")),
                groups = listOf(CatalogueGroup(text("Popular this week"), listOf(link("popular")), books("Frankenstein"), emptyList())),
            ),
        )
        runCurrent()

        h.browser.openBook(h.loaded.books.single().key)
        val openBook = assertIs<CatalogueBrowseNavigation.OpenBook>(h.state.navigation)
        assertEquals(FakeTarget("root"), openBook.book.listing)
        assertEquals(listOf(moby), openBook.book.publications)
        h.browser.navigationHandled()
        assertNull(h.state.navigation)

        h.browser.openFolder(h.loaded.folders.single().key)
        assertEquals(CatalogueBrowseNavigation.OpenPage(CataloguePlace(FakeTarget("subjects"), "By subject", fromEntryWithoutFiles = true)), h.state.navigation)

        h.browser.openSeeAll(h.loaded.shelves.single().key)
        assertEquals(CatalogueBrowseNavigation.OpenPage(CataloguePlace(FakeTarget("popular"), "Popular this week")), h.state.navigation)

        h.browser.openBook(h.loaded.shelves.single().books.single().key)
        assertEquals("Frankenstein", assertIs<CatalogueBrowseNavigation.OpenBook>(h.state.navigation).book.publications.single().title.display())
    }

    @Test fun a_page_that_is_one_book_is_replaced_by_the_book_page() = runTest {
        val single = Harness(this, place("1342", "Pride and Prejudice", fromEntry = true))
        val editions = Harness(this, place("84", "Frankenstein", fromEntry = true))
        val document = Harness(this, place("2701"))
        runCurrent()
        val pride = book("Pride and Prejudice")
        single.repo.answer("1342", feed("1342", books = listOf(pride)))
        editions.repo.answer("84", feed("84", books = listOf(book("Frankenstein", id = "a"), book("Frankenstein", id = "b", edition = "1831"))))
        document.repo.answer("2701", bookDocument(book("Moby Dick"), "2701"))
        runCurrent()

        val replaced = assertIs<CatalogueBrowseNavigation.ReplaceWithBook>(single.state.navigation)
        assertEquals(FakeTarget("1342"), replaced.book.listing)
        assertEquals(listOf(pride), replaced.book.publications)
        assertEquals(CatalogueBrowseContent.FirstLoad, single.state.content)
        assertEquals(2, assertIs<CatalogueBrowseNavigation.ReplaceWithBook>(editions.state.navigation).book.publications.size)
        assertEquals("Moby Dick", assertIs<CatalogueBrowseNavigation.ReplaceWithBook>(document.state.navigation).book.publications.single().title.display())
    }

    @Test fun the_same_book_listed_several_times_but_not_groupable_is_a_list_with_telling_lines() = runTest {
        // Not opened from an entry without files, so the grouping rule says "a list".
        val h = Harness(this, place("treasure", "Treasure Island"))
        runCurrent()
        h.repo.answer(
            "treasure",
            feed(
                "treasure",
                books = listOf(
                    book("Treasure Island", id = "1", edition = "Illustrated edition", year = "1911", files = 2),
                    book("Treasure Island", id = "2", year = "2004"),
                    book("Treasure Island", id = "3", files = 3),
                ),
            ),
        )
        runCurrent()
        assertNull(h.state.navigation)
        assertEquals(3, h.loaded.sameBookCount)
        assertEquals("Treasure Island", h.state.title)
        assertEquals(
            listOf(CatalogueTellingLine("Illustrated edition", "1911", 2), CatalogueTellingLine(null, "2004", 1), CatalogueTellingLine(null, null, 3)),
            h.loaded.books.map { it.telling },
        )
    }

    // --- paging -------------------------------------------------------------------------

    @Test fun day_and_night_load_the_next_page_near_the_end_through_the_next_link_only() = runTest {
        val h = Harness(this, place("p1"))
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A", "B"), next = "p2"))
        runCurrent()
        assertEquals(CataloguePaging.Auto, h.loaded.more)
        assertEquals(listOf("p1"), h.repo.requested)

        h.browser.onNearEnd()
        h.browser.onNearEnd()
        runCurrent()
        assertEquals(CataloguePaging.Loading, h.loaded.more)
        assertEquals(listOf("p1", "p2"), h.repo.requested)

        h.repo.answer("p2", feed("p2", books = books("C")))
        runCurrent()
        assertEquals(listOf("A", "B", "C"), h.titles)
        assertEquals(CataloguePaging.None, h.loaded.more)
        h.browser.onNearEnd()
        h.browser.loadMore()
        runCurrent()
        assertEquals(listOf("p1", "p2"), h.repo.requested)
        assertEquals(h.loaded.books.map { it.key }.distinct().size, 3)
    }

    @Test fun e_ink_never_loads_by_itself_and_shows_load_more() = runTest {
        val h = Harness(this, place("p1"))
        h.browser.setAutoLoad(false)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A"), next = "p2"))
        runCurrent()
        assertEquals(CataloguePaging.Button, h.loaded.more)
        h.browser.onNearEnd()
        runCurrent()
        assertEquals(listOf("p1"), h.repo.requested)

        h.browser.loadMore()
        runCurrent()
        assertEquals(CataloguePaging.Loading, h.loaded.more)
        h.repo.answer("p2", feed("p2", books = books("B"), next = "p3"))
        runCurrent()
        assertEquals(listOf("A", "B"), h.titles)
        assertEquals(CataloguePaging.Button, h.loaded.more)
    }

    @Test fun a_failed_next_page_keeps_the_books_never_looks_like_the_end_and_retries_the_same_link() = runTest {
        val h = Harness(this, place("p1"))
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A", "B"), next = "p2"))
        runCurrent()
        h.browser.onNearEnd()
        runCurrent()
        h.repo.answer("p2", failure(CatalogueErrorKind.Timeout))
        runCurrent()
        assertEquals(listOf("A", "B"), h.titles)
        assertEquals(CataloguePaging.Failed, h.loaded.more)

        // Scrolling does not try again on its own.
        h.browser.onNearEnd()
        runCurrent()
        assertEquals(listOf("p1", "p2"), h.repo.requested)

        h.browser.loadMore()
        runCurrent()
        assertEquals(CataloguePaging.Loading, h.loaded.more)
        assertEquals(listOf("p1", "p2", "p2"), h.repo.requested)
        h.repo.answer("p2", feed("p2", books = books("C")))
        runCurrent()
        assertEquals(listOf("A", "B", "C"), h.titles)
    }

    @Test fun at_the_page_limit_the_earliest_pages_go_and_come_back_when_scrolling_up() = runTest {
        val h = Harness(this, place("p1"), maxPages = 3)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("1a", "1b"), next = "p2", facets = listOf(CatalogueFacetGroup(text("Language"), listOf(option("English", active = true)), null))))
        runCurrent()
        (2..5).forEach { page ->
            h.browser.onNearEnd()
            runCurrent()
            h.repo.answer("p$page", feed("p$page", books = books("${page}a", "${page}b"), next = "p${page + 1}"))
            runCurrent()
        }
        assertEquals(listOf("3a", "3b", "4a", "4b", "5a", "5b"), h.titles)
        assertEquals(CataloguePaging.Auto, h.loaded.earlier)
        assertEquals(CataloguePaging.Auto, h.loaded.more)
        assertEquals(1, h.loaded.chips.size)
        val keysBefore = h.loaded.books.associate { it.title to it.key }

        // Scrolling back refetches the page that was dropped last, and the far end gives way.
        h.browser.onNearStart()
        runCurrent()
        assertEquals(CataloguePaging.Loading, h.loaded.earlier)
        assertEquals("p2", h.repo.requested.last())
        h.repo.answer("p2", feed("p2", books = books("2a", "2b"), next = "p3"))
        runCurrent()
        assertEquals(listOf("2a", "2b", "3a", "3b", "4a", "4b"), h.titles)
        assertEquals(keysBefore["3a"], h.loaded.books.first { it.title == "3a" }.key)
        assertEquals(CataloguePaging.Auto, h.loaded.earlier)

        // Forward again continues from the last page that is still loaded.
        h.browser.onNearEnd()
        runCurrent()
        assertEquals("p5", h.repo.requested.last())

        // Back to the very first page: nothing earlier is left.
        h.repo.answer("p5", failure(CatalogueErrorKind.Timeout))
        h.browser.onNearStart()
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("1a", "1b"), next = "p2"))
        runCurrent()
        assertEquals(listOf("1a", "1b", "2a", "2b", "3a", "3b"), h.titles)
        assertEquals(CataloguePaging.None, h.loaded.earlier)
    }

    @Test fun a_failed_earlier_page_keeps_the_books_and_can_be_tried_again() = runTest {
        val h = Harness(this, place("p1"), maxPages = 1)
        h.browser.setAutoLoad(false)
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("1"), next = "p2"))
        runCurrent()
        h.browser.loadMore()
        runCurrent()
        h.repo.answer("p2", feed("p2", books = books("2")))
        runCurrent()
        assertEquals(listOf("2"), h.titles)
        assertEquals(CataloguePaging.Button, h.loaded.earlier)
        h.browser.onNearStart()
        runCurrent()
        assertEquals(listOf("p1", "p2"), h.repo.requested)

        h.browser.loadEarlier()
        runCurrent()
        h.repo.answer("p1", failure(CatalogueErrorKind.ServerError))
        runCurrent()
        assertEquals(CataloguePaging.Failed, h.loaded.earlier)
        assertEquals(listOf("2"), h.titles)
        h.browser.loadEarlier()
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("1"), next = "p2"))
        runCurrent()
        assertEquals(listOf("1"), h.titles)
        assertEquals(CataloguePaging.Button, h.loaded.more)
    }

    // --- search -------------------------------------------------------------------------

    private suspend fun TestScope.searchable(): Harness {
        val h = Harness(this)
        runCurrent()
        h.repo.answer("root", feed(search = true, folders = listOf(folder("Fiction"))))
        runCurrent()
        return h
    }

    @Test fun search_results_are_book_rows_and_clearing_returns_to_the_page_as_it_was() = runTest {
        val h = searchable()
        h.browser.onSearchTextChange(" whale ")
        assertEquals(" whale ", h.state.searchText)
        assertNull(h.state.searchQuery)
        h.browser.submitSearch()
        runCurrent()
        assertEquals("whale", h.state.searchQuery)
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        assertEquals(listOf("root", "search:whale"), h.repo.requested)

        h.repo.answer("search:whale", feed("results", books = books("Moby Dick", "A Whaleman's Log"), next = "results-2"))
        runCurrent()
        assertEquals(listOf("Moby Dick", "A Whaleman's Log"), h.titles)
        assertEquals(CataloguePaging.Auto, h.loaded.more)

        h.browser.clearSearch()
        runCurrent()
        assertNull(h.state.searchQuery)
        assertEquals("", h.state.searchText)
        assertEquals(listOf("Fiction"), h.loaded.folders.map { it.title })
        assertEquals(listOf("root", "search:whale"), h.repo.requested)
    }

    @Test fun a_search_with_no_books_is_no_results_and_an_empty_search_is_not_sent() = runTest {
        val h = searchable()
        h.browser.onSearchTextChange("   ")
        h.browser.submitSearch()
        runCurrent()
        assertNull(h.state.searchQuery)
        h.browser.onSearchTextChange("whalle")
        h.browser.submitSearch()
        runCurrent()
        h.repo.answer("search:whalle", feed("results"))
        runCurrent()
        assertEquals(CatalogueBrowseContent.NoResults("whalle"), h.state.content)
    }

    @Test fun a_page_without_search_never_searches() = runTest {
        val h = Harness(this)
        runCurrent()
        h.repo.answer("root", feed(folders = listOf(folder("Fiction"))))
        runCurrent()
        h.browser.onSearchTextChange("whale")
        h.browser.submitSearch()
        runCurrent()
        assertNull(h.state.searchQuery)
        assertEquals(listOf("root"), h.repo.requested)
    }

    // --- filters ------------------------------------------------------------------------

    private val languages = CatalogueFacetGroup(
        name = text("Language"),
        options = listOf(option("English", active = true, count = 41_208), option("French", count = 3_982), option("German")),
        allOption = option("Any language", target = "facet-any"),
    )
    private val sort = CatalogueFacetGroup(text("Sort"), listOf(option("Most popular", active = true), option("Newest")), null)

    @Test fun chips_show_each_groups_current_value_and_the_sheet_lists_its_options_all_first() = runTest {
        val h = Harness(this, place("popular", "Popular"))
        runCurrent()
        h.repo.answer("popular", feed("popular", books = books("A", "B"), facets = listOf(languages, sort)))
        runCurrent()
        assertEquals(listOf(CatalogueFilterChip(0, "Language", "English"), CatalogueFilterChip(1, "Sort", "Most popular")), h.loaded.chips)

        h.browser.openFilter(0)
        val sheet = h.state.filterSheet!!
        assertEquals("Language", sheet.group)
        assertEquals(4, sheet.optionCount)
        assertFalse(sheet.searchable)
        assertEquals(
            listOf(
                CatalogueFilterOption(0, "Any language", null, false),
                CatalogueFilterOption(1, "English", 41_208, true),
                CatalogueFilterOption(2, "French", 3_982, false),
                CatalogueFilterOption(3, "German", null, false),
            ),
            sheet.options,
        )
        h.browser.closeFilter()
        assertNull(h.state.filterSheet)
    }

    @Test fun a_long_option_list_can_be_searched_and_keeps_its_option_numbers() = runTest {
        val many = CatalogueFacetGroup(text("Language"), (1..13).map { option("Language $it") }, null)
        val h = Harness(this, place("popular"))
        runCurrent()
        h.repo.answer("popular", feed("popular", books = books("A", "B"), facets = listOf(many, sort)))
        runCurrent()
        assertEquals(CatalogueFilterChip(0, "Language", "Language"), h.loaded.chips.first())
        h.browser.openFilter(1)
        assertFalse(h.state.filterSheet!!.searchable)
        h.browser.openFilter(0)
        assertTrue(h.state.filterSheet!!.searchable)
        h.browser.onFilterSearchChange("GUAGE 1")
        assertEquals(listOf(0, 9, 10, 11, 12), h.state.filterSheet!!.options.map { it.index })
        assertEquals(13, h.state.filterSheet!!.optionCount)
    }

    @Test fun choosing_an_option_applies_it_at_once_and_the_current_one_only_closes_the_sheet() = runTest {
        val h = Harness(this, place("popular", "Popular"))
        runCurrent()
        h.repo.answer("popular", feed("popular", books = books("A", "B"), facets = listOf(languages)))
        runCurrent()
        h.browser.openFilter(0)
        h.browser.chooseFilter(1)
        runCurrent()
        assertNull(h.state.filterSheet)
        assertEquals(listOf("popular"), h.repo.requested)

        h.browser.openFilter(0)
        h.browser.chooseFilter(2)
        runCurrent()
        assertNull(h.state.filterSheet)
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        assertEquals(listOf("popular", "facet-French"), h.repo.requested)
        h.repo.answer("facet-French", feed("french", books = books("Les Misérables", "Candide"), facets = listOf(languages.copy(options = listOf(option("English"), option("French", active = true))))))
        runCurrent()
        assertEquals(listOf("Les Misérables", "Candide"), h.titles)
        assertEquals("French", h.loaded.chips.single().value)
        assertEquals("Popular", h.state.title)
        assertNull(h.state.navigation)
    }

    // --- late results -------------------------------------------------------------------

    @Test fun a_new_search_while_one_is_running_ignores_the_first_answer() = runTest {
        val h = searchable()
        h.browser.onSearchTextChange("whale")
        h.browser.submitSearch()
        runCurrent()
        h.browser.onSearchTextChange("shark")
        h.browser.submitSearch()
        runCurrent()

        h.repo.answer("search:whale", feed("whales", books = books("Moby Dick")))
        runCurrent()
        assertEquals("shark", h.state.searchQuery)
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)

        h.repo.answer("search:shark", feed("sharks", books = books("Jaws")))
        runCurrent()
        assertEquals(listOf("Jaws"), h.titles)
    }

    @Test fun an_answer_for_a_cleared_search_does_not_replace_the_page() = runTest {
        val h = searchable()
        h.browser.onSearchTextChange("whale")
        h.browser.submitSearch()
        runCurrent()
        h.browser.clearSearch()
        h.repo.answer("search:whale", feed("whales", books = books("Moby Dick")))
        runCurrent()
        assertNull(h.state.searchQuery)
        assertEquals(listOf("Fiction"), h.loaded.folders.map { it.title })
        assertTrue(h.loaded.books.isEmpty())
    }

    @Test fun a_filter_change_during_a_page_load_drops_that_page() = runTest {
        val h = Harness(this, place("popular"))
        runCurrent()
        h.repo.answer("popular", feed("popular", books = books("A", "B"), next = "popular-2", facets = listOf(languages)))
        runCurrent()
        h.browser.onNearEnd()
        runCurrent()
        h.browser.openFilter(0)
        h.browser.chooseFilter(2)
        runCurrent()

        h.repo.answer("popular-2", feed("popular-2", books = books("C")))
        runCurrent()
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        h.repo.answer("facet-French", feed("french", books = books("Candide", "Germinal")))
        runCurrent()
        assertEquals(listOf("Candide", "Germinal"), h.titles)
        assertEquals(CataloguePaging.None, h.loaded.more)
    }

    @Test fun leaving_during_the_first_load_means_its_answer_changes_nothing() = runTest {
        val h = Harness(this)
        runCurrent()
        val before = h.state
        h.browser.cancel()
        h.repo.answer("root", feed(folders = listOf(folder("Fiction"))))
        runCurrent()
        assertEquals(before, h.state)
    }

    @Test fun turning_the_catalogue_off_closes_the_screen_and_a_late_answer_stays_out() = runTest {
        val h = Harness(this, place("p1"))
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A", "B"), next = "p2"))
        runCurrent()
        h.browser.onNearEnd()
        runCurrent()

        h.gateway.source.value = null
        runCurrent()
        assertTrue(h.state.closed)
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        h.repo.answer("p2", feed("p2", books = books("C")))
        runCurrent()
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        // Nothing can be started on a closed screen.
        h.browser.retry()
        h.browser.onSearchTextChange("x")
        h.browser.submitSearch()
        runCurrent()
        assertEquals(listOf("p1", "p2"), h.repo.requested)
    }

    @Test fun switching_profile_closes_the_screen_even_when_a_catalogue_has_the_same_id_there() = runTest {
        val h = Harness(this)
        runCurrent()
        h.gateway.source.value = CatalogueBrowseSource("profile-2", "Their catalogue", "$ORIGIN/opds")
        runCurrent()
        assertTrue(h.state.closed)
        h.repo.answer("root", feed(folders = listOf(folder("Private shelf"))))
        runCurrent()
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        assertEquals("Project Gutenberg", h.state.catalogueName)
    }

    @Test fun a_catalogue_that_is_already_gone_closes_at_once_without_a_request() = runTest {
        val gateway = FakeGateway().apply { source.value = null }
        val browser = CatalogueBrowser(SOURCE, null, gateway, FakeLibrary(), backgroundScope)
        runCurrent()
        assertTrue(browser.state.value.closed)
        assertTrue(gateway.repository.requested.isEmpty())
    }

    @Test fun a_renamed_catalogue_keeps_the_screen_open() = runTest {
        val h = Harness(this)
        runCurrent()
        h.gateway.source.value = h.gateway.source.value!!.copy(name = "Gutenberg")
        runCurrent()
        assertFalse(h.state.closed)
        assertEquals("Gutenberg", h.state.catalogueName)
        assertEquals(listOf("root"), h.repo.requested)
    }

    // --- links to the local network -----------------------------------------------------

    @Test fun a_link_to_a_device_on_the_local_network_is_asked_about_before_it_is_followed() = runTest {
        val h = Harness(this)
        runCurrent()
        h.repo.answer("root", feed(folders = listOf(folder("My NAS", target = "nas", href = "http://192.168.1.20/opds"), folder("Fiction"))))
        runCurrent()
        val nas = h.loaded.folders.first().key

        h.browser.openFolder(nas)
        assertEquals("192.168.1.20", h.state.localNetworkHost)
        assertNull(h.state.navigation)
        h.browser.dismissLocalNetwork()
        assertNull(h.state.localNetworkHost)
        assertNull(h.state.navigation)

        h.browser.openFolder(nas)
        h.browser.confirmLocalNetwork()
        assertNull(h.state.localNetworkHost)
        assertEquals(
            CatalogueBrowseNavigation.OpenPage(CataloguePlace(FakeTarget("nas"), "My NAS", fromEntryWithoutFiles = true, localNetworkHost = "192.168.1.20")),
            h.state.navigation,
        )
        h.browser.navigationHandled()
        h.browser.openFolder(h.loaded.folders.last().key)
        assertNull(h.state.localNetworkHost)
    }

    @Test fun a_next_page_on_the_local_network_is_never_loaded_without_asking() = runTest {
        val h = Harness(this, place("p1"))
        runCurrent()
        h.repo.answer("p1", feed("p1", books = books("A")).let { it.copy(pagination = it.pagination.copy(next = link("p2", "http://10.0.0.7/p2"))) })
        runCurrent()
        assertEquals(CataloguePaging.Button, h.loaded.more)
        h.browser.onNearEnd()
        runCurrent()
        assertEquals(listOf("p1"), h.repo.requested)
        h.browser.loadMore()
        runCurrent()
        assertEquals("10.0.0.7", h.state.localNetworkHost)
        assertEquals(listOf("p1"), h.repo.requested)
        h.browser.confirmLocalNetwork()
        runCurrent()
        assertEquals(listOf("p1", "p2"), h.repo.requested)
    }

    @Test fun a_page_that_was_redirected_to_the_local_network_is_held_back_until_the_user_agrees() = runTest {
        val h = Harness(this, place("shelf"))
        val agreed = Harness(this, CataloguePlace(FakeTarget("shelf"), localNetworkHost = "192.168.1.20"))
        runCurrent()
        val redirected = feed("shelf", books = books("Private", "Diary"), privateNetwork = true, responseUrl = "http://192.168.1.20/shelf")
        h.repo.answer("shelf", redirected)
        agreed.repo.answer("shelf", redirected)
        runCurrent()
        assertEquals("192.168.1.20", h.state.localNetworkHost)
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)
        assertEquals(listOf("Private", "Diary"), agreed.titles)

        h.browser.dismissLocalNetwork()
        assertTrue(h.state.closed)
        assertEquals(CatalogueBrowseContent.FirstLoad, h.state.content)

        val opened = Harness(this, place("shelf"))
        runCurrent()
        opened.repo.answer("shelf", redirected)
        runCurrent()
        opened.browser.confirmLocalNetwork()
        runCurrent()
        assertEquals(listOf("Private", "Diary"), opened.titles)
    }

    // --- sign-in ------------------------------------------------------------------------

    @Test fun a_page_that_needs_an_account_opens_the_sign_in_sheet_and_reloads_after_signing_in() = runTest {
        val h = Harness(this, place("private", "Private shelf"))
        runCurrent()
        h.repo.answer("private", failure(CatalogueErrorKind.SignInNeeded))
        runCurrent()
        assertEquals(CatalogueBrowseContent.SignInNeeded, h.state.content)
        assertEquals(CatalogueSignInState(), h.state.signIn)

        h.browser.signIn(" rox ", "")
        runCurrent()
        assertTrue(h.gateway.savedAccounts.isEmpty())
        assertEquals(CatalogueSignInState(working = true), h.state.signIn)
        assertEquals(listOf("private", "private"), h.repo.requested)

        h.repo.answer("private", feed("private", books = books("Diary", "Letters")))
        runCurrent()
        assertNull(h.state.signIn)
        assertEquals(listOf("Diary", "Letters"), h.titles)
        assertEquals(listOf(OpdsAccountDetails("rox", "")), h.gateway.savedAccounts)
    }

    @Test fun wrong_details_keep_the_sheet_open_and_closing_it_leaves_the_page() = runTest {
        val h = Harness(this, place("private"))
        runCurrent()
        h.repo.answer("private", failure(CatalogueErrorKind.SignInNeeded))
        runCurrent()
        h.browser.signIn("rox", "wrong")
        runCurrent()
        h.repo.answer("private", failure(CatalogueErrorKind.SignInNeeded))
        runCurrent()
        assertEquals(CatalogueSignInState(wrongDetails = true), h.state.signIn)
        assertEquals(CatalogueBrowseContent.SignInNeeded, h.state.content)
        assertTrue(h.gateway.savedAccounts.isEmpty())

        h.browser.signIn("", "x")
        runCurrent()
        assertEquals(0, h.gateway.savedAccounts.size)

        h.browser.dismissSignIn()
        assertNull(h.state.signIn)
        assertTrue(h.state.closed)
    }

    @Test fun returning_from_a_book_refreshes_library_rows_without_refetching_or_losing_the_list() = runTest {
        val library = FakeLibrary()
        val h = Harness(this, place("popular"), library = library)
        runCurrent(); h.repo.answer("popular", feed("popular", books = books("Treasure Island", "Diary"))); runCurrent()
        assertFalse(h.loaded.books.first().inLibrary)
        library.inLibrary = setOf("id:Treasure Island")
        h.browser.onReturn(); runCurrent()
        assertTrue(h.loaded.books.first().inLibrary)
        assertFalse(h.loaded.books.last().inLibrary)
        assertEquals(listOf("popular"), h.repo.requested)
        library.inLibrary = emptySet()
        h.browser.onReturn(); runCurrent(); assertFalse(h.loaded.books.first().inLibrary)
    }

    @Test fun signing_in_to_a_search_verifies_the_search_not_the_public_root() = runTest {
        val h = Harness(this)
        runCurrent(); h.repo.answer("root", feed(search = true)); runCurrent()
        h.browser.onSearchTextChange("diary"); h.browser.submitSearch(); runCurrent()
        h.repo.answer("search:diary", failure(CatalogueErrorKind.SignInNeeded)); runCurrent()
        h.browser.signIn("rok", "wrong"); runCurrent()
        assertEquals("search:diary", h.repo.requested.last())
        h.repo.answer("search:diary", failure(CatalogueErrorKind.SignInNeeded)); runCurrent()
        assertTrue(h.gateway.savedAccounts.isEmpty())
        assertTrue(h.state.signIn!!.wrongDetails)
    }
}
