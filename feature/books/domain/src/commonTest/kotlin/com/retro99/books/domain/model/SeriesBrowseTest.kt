package com.retro99.books.domain.model

import com.retro99.books.domain.model.links.*
import com.retro99.server.api.ServerType
import kotlin.test.*

class SeriesBrowseTest {
    private fun membership(name: String = "The Salt Roads", number: Double? = null) =
        SeriesDomainModel("membership", name, null, number, null, null)
    private fun entry(book: BookDomainModel, progress: Double? = null) = BookWithProgressDomainModel(book,
        progress?.let { BookProgressInfoDomainModel(book.uuid, it, null, false, false, false) })

    @Test fun mergesNamesRetainsSourcesAndCountsLinkedBookOnce() {
        val st = testServerBook("st").copy(series = listOf(membership(number = 2.0)))
        val abs = testServerBook("abs", ServerType.Audiobookshelf).copy(series = listOf(membership(" the SALT  roads ", 2.0)))
        val catalogues = listOf(
            membership().copy(sources = listOf(SeriesSource(st.serverId, "st-series"))),
            membership("THE SALT ROADS").copy(sources = listOf(SeriesSource(abs.serverId, "abs-series"))),
        )
        val series = buildSeriesBrowse(catalogues, listOf(entry(st, .62), entry(abs, .43)),
            listOf(BookLink("linked", setOf(st.copyKey(), abs.copyKey())))).single()
        assertEquals(1, series.books.size)
        assertEquals(setOf(st.serverId, abs.serverId), series.sources.map { it.serverId }.toSet())
        assertEquals(.62, series.progress)
    }

    @Test fun libraryPrimaryDoesNotLoseServerMembershipOrItsOwnProgress() {
        val local = testLibraryBook("local")
        val st = testServerBook("st").copy(series = listOf(membership(number = 2.5)))
        val series = buildSeriesBrowse(emptyList(), listOf(entry(local, .3), entry(st, .8)),
            listOf(BookLink("linked", setOf(local.copyKey(), st.copyKey())))).single()
        assertEquals(local.uuid, series.books.single().book.uuid)
        assertEquals(2.5, series.books.single().position)
        assertEquals(.3, series.progress) // Never choose furthest as the winner.
    }

    @Test fun preservesMultipleMembershipsAndFractionalAndUnnumberedOrder() {
        val first = testServerBook("first", title = "First").copy(series = listOf(membership(number = 1.0)))
        val fractional = testServerBook("fractional").copy(series = listOf(membership(number = 2.5), membership("Other", 4.0)))
        val last = testServerBook("last", title = "A title").copy(series = listOf(membership()))
        val series = buildSeriesBrowse(emptyList(), listOf(entry(last), entry(fractional, .5), entry(first, 1.0)), emptyList())
        val salt = series.single { normalisedSeriesName(it.name) == "the salt roads" }
        assertEquals(listOf(1.0, 2.5, null), salt.books.map { it.position })
        assertEquals(1, salt.finishedCount)
        assertEquals(1, salt.inProgressCount)
        assertEquals(.5, salt.progress)
        assertEquals(4.0, series.single { it.name == "Other" }.books.single().position)
    }

    @Test fun sourceQualifiedUnlinkedKeysDoNotCollide() {
        val first = testServerBook("same", serverId = "one").copy(series = listOf(membership()))
        val second = testServerBook("same", serverId = "two").copy(series = listOf(membership()))
        val books = buildSeriesBrowse(emptyList(), listOf(entry(first), entry(second)), emptyList()).single().books
        assertEquals(2, books.size)
        assertEquals(2, books.map { it.key }.toSet().size)
    }
}
