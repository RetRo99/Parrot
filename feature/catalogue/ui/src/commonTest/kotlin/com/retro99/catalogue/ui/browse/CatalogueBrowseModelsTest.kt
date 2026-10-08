package com.retro99.catalogue.ui.browse

import com.retro99.base.result.AppError
import com.retro99.catalogue.ui.navigation.CatalogueBookPlace
import com.retro99.catalogue.ui.navigation.CataloguePlace
import com.retro99.catalogue.ui.navigation.CatalogueRouteReferences
import com.retro99.server.api.CatalogueAccessStatus
import com.retro99.server.api.CatalogueSourceStatus
import com.retro99.server.api.ServerAccessState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueBrowseModelsTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // 2026-10-09T12:00:00Z
    private val now = 1_791_547_200_000L
    private fun ago(millis: Long) = catalogueTimeAgo(now - millis, now, "UTC")

    @Test fun times_follow_the_copy_reference() {
        assertEquals(CatalogueTimeAgo.JustNow, ago(59_000))
        assertEquals(CatalogueTimeAgo.JustNow, catalogueTimeAgo(now + hour, now, "UTC"))
        assertEquals(CatalogueTimeAgo.Minutes(1), ago(minute))
        assertEquals(CatalogueTimeAgo.Minutes(59), ago(59 * minute + 59_000))
        assertEquals(CatalogueTimeAgo.Hours(1), ago(hour))
        // 13:00 the day before is still "23 h ago": hours win within 24 hours.
        assertEquals(CatalogueTimeAgo.Hours(23), ago(23 * hour))
        assertEquals(CatalogueTimeAgo.Yesterday, ago(24 * hour))
        assertEquals(CatalogueTimeAgo.Yesterday, ago(35 * hour))
        // 36 hours back is 00:00 yesterday; one minute more is the day before.
        assertEquals(CatalogueTimeAgo.Days(2), ago(36 * hour + minute))
        assertEquals(CatalogueTimeAgo.Days(6), ago(6 * day + hour))
        val thisYear = assertIs<CatalogueTimeAgo.OnDate>(ago(7 * day))
        assertTrue(thisYear.date.isNotBlank() && "2026" !in thisYear.date.takeLast(4), thisYear.date)
        val otherYear = assertIs<CatalogueTimeAgo.OnDate>(ago(400 * day))
        assertTrue("2025" in otherYear.date, otherYear.date)
    }

    @Test fun this_years_dates_lose_only_a_trailing_year() {
        assertEquals("12 Mar", withoutTrailingYear("12 Mar 2026", 2026))
        assertEquals("Mar 12", withoutTrailingYear("Mar 12, 2026", 2026))
        assertEquals("12 Mar 2025", withoutTrailingYear("12 Mar 2025", 2026))
        assertEquals("2026-03-12", withoutTrailingYear("2026-03-12", 2026))
        assertEquals("2026", withoutTrailingYear("2026", 2026))
    }

    @Test fun telling_lines_are_built_from_what_the_entry_has() {
        assertEquals(CatalogueTellingLine("Illustrated edition", "1911", 2), book("T", edition = "Illustrated edition", year = "1911", files = 2).tellingLine())
        assertEquals(CatalogueTellingLine(null, "2004", 1), book("T").copy(published = "2004-05-01T00:00:00Z").tellingLine())
        assertEquals(CatalogueTellingLine(null, null, 3), book("T", edition = " ", year = "n.d.", files = 3).tellingLine())
    }

    @Test fun errors_outside_the_catalogue_kinds_still_map_to_a_reason() {
        assertEquals(CatalogueLoadProblem.Failed(CataloguePageFailure.NotACatalogue), AppError.ApiError(400, "WebPage").toLoadProblem())
        assertEquals(CatalogueLoadProblem.Failed(CataloguePageFailure.NotACatalogue), AppError.ApiError(400, "NotCatalogue").toLoadProblem())
        assertEquals(CatalogueLoadProblem.Failed(CataloguePageFailure.NotAllowed), AppError.ApiError(401, "SignInUnsupported").toLoadProblem())
        assertEquals(CatalogueLoadProblem.Failed(CataloguePageFailure.NotAllowed), AppError.ApiError(400, "SecurityPolicy").toLoadProblem())
        assertEquals(CatalogueLoadProblem.Failed(CataloguePageFailure.CatalogueError), AppError.ApiError(400, "ForeignCatalogueTarget").toLoadProblem())
        assertEquals(CatalogueLoadProblem.Failed(CataloguePageFailure.CatalogueError), AppError.UnknownError(IllegalStateException("x")).toLoadProblem())
    }

    @Test fun the_grouping_rule_matches_phase_1() {
        val same = listOf(book("Frankenstein", id = "a"), book("Frankenstein", id = "b"))
        // A listing entry without files, an unpaginated list, one title: one book.
        assertEquals(CatalogueOpening.OneBook, decideCatalogueOpening(true, feed(books = same)))
        assertEquals(CatalogueOpening.OneBook, decideCatalogueOpening(true, feed(books = same.take(1))))
        // The entry had files itself, or the list is paginated: a list, drawn as the same book several times.
        assertEquals(CatalogueOpening.SameBookList, decideCatalogueOpening(false, feed(books = same)))
        assertEquals(CatalogueOpening.SameBookList, decideCatalogueOpening(true, feed(books = same, next = "p2")))
        assertEquals(CatalogueOpening.SameBookList, decideCatalogueOpening(true, feed(books = same, first = "p1")))
        // One entry is just a list of one; different titles or authors are an ordinary list.
        assertEquals(CatalogueOpening.Page, decideCatalogueOpening(false, feed(books = same.take(1))))
        assertEquals(CatalogueOpening.Page, decideCatalogueOpening(true, feed(books = listOf(book("A"), book("B")))))
        assertEquals(CatalogueOpening.Page, decideCatalogueOpening(false, feed(books = listOf(book("A", "X", id = "1"), book("A", "Y", id = "2")))))
        assertEquals(CatalogueOpening.Page, decideCatalogueOpening(true, feed(books = same, folders = listOf(folder("More")))))
        assertEquals(CatalogueOpening.Page, decideCatalogueOpening(true, feed()))
    }

    // --- route references ---------------------------------------------------------------

    private fun source(id: String, address: String = "https://$id.example/opds", enabled: Boolean = true, access: ServerAccessState = ServerAccessState.Public) =
        CatalogueSourceStatus(ServerConfig(id = id, name = id, type = ServerType.Opds, baseUrl = address, addedAt = 0, enabled = enabled), CatalogueAccessStatus(access))

    @Test fun references_resolve_to_places_and_books_and_say_nothing_when_printed() {
        val references = CatalogueRouteReferences()
        val place = CataloguePlace(FakeTarget("p"), "Popular", fromEntryWithoutFiles = true)
        val bookPlace = CatalogueBookPlace(FakeTarget("p"), listOf(book("Moby Dick")))
        val pageRef = references.referenceTo("a", place)
        val bookRef = references.referenceTo("a", bookPlace)
        assertEquals(place, references.place("a", pageRef))
        assertEquals(FakeTarget("p"), references.target("a", pageRef))
        assertEquals(bookPlace, references.book("a", bookRef))
        assertNull(references.book("a", pageRef))
        assertNull(references.place("b", pageRef))
        assertEquals(CataloguePlace(FakeTarget("t")), references.place("a", references.referenceTo("a", FakeTarget("t"))))
        assertTrue("Moby" !in bookPlace.toString() && "Popular" !in place.toString())
    }

    @Test fun references_go_when_a_catalogue_is_turned_off_removed_or_moved_and_all_go_with_the_profile() = runTest {
        val references = CatalogueRouteReferences()
        val profiles = MutableStateFlow("profile-1")
        val sources = MutableStateFlow(listOf(source("a"), source("b"), source("c"), source("d")))
        backgroundScope.launch { forgetStaleCatalogueReferences(references, profiles, sources) }
        runCurrent()
        val refs = listOf("a", "b", "c", "d").associateWith { references.referenceTo(it, FakeTarget(it)) }
        fun alive() = refs.filter { (id, ref) -> references.target(id, ref) != null }.keys

        // Nothing is lost by starting to watch.
        assertEquals(setOf("a", "b", "c", "d"), alive())
        sources.value = listOf(source("a"), source("b", enabled = false), source("c", address = "https://moved.example/opds"))
        runCurrent()
        assertEquals(setOf("a"), alive())
        sources.value = listOf(source("a", access = ServerAccessState.TurnedOff))
        runCurrent()
        assertEquals(emptySet(), alive())

        val again = references.referenceTo("a", FakeTarget("a"))
        profiles.value = "profile-2"
        runCurrent()
        assertNull(references.target("a", again))
        assertNotNull(references.referenceTo("a", FakeTarget("a")))
    }
}
