package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.TocItemUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentsTreeTest {

    private fun toc(vararg entries: Triple<String, String, Int>): List<TocItemUiModel> =
        entries.map { (href, title, level) -> TocItemUiModel(href = href, title = title, level = level) }

    private fun position(href: String, progression: Double? = 0.5, position: Int? = null, total: Double? = null) =
        PositionUiModel(
            createdAt = "", href = href, type = "application/xhtml+xml", title = null,
            progression = progression, position = position, totalProgression = total,
            chapterIndex = null, totalChapters = null,
        )

    private fun bookmark(
        href: String,
        position: Int? = null,
        progression: Double? = null,
        total: Double? = null,
    ) = BookmarkUiModel(
        id = "id", locatorHref = href, locatorType = null, locatorTitle = null,
        progression = progression, totalProgression = total, chapterIndex = null,
        position = position, createdAt = "2026-01-01T00:00:00Z",
    )

    // Href normalisation

    @Test fun normalisesFragmentsRelativePrefixesAndEscapes() {
        assertEquals("text/ch1.xhtml", normaliseTocHref("text/ch1.xhtml#part-2"))
        assertEquals("text/ch1.xhtml", normaliseTocHref("./text/ch1.xhtml"))
        assertEquals("text/ch1.xhtml", normaliseTocHref("text/sub/../ch1.xhtml"))
        assertEquals("text/ch1.xhtml", normaliseTocHref("/text//ch1.xhtml"))
        assertEquals("text/my chapter.xhtml", normaliseTocHref("text/my%20chapter.xhtml"))
        assertEquals("text/ch1.xhtml", normaliseTocHref("text/ch1.xhtml?query=1"))
    }

    @Test fun keepsFragmentSeparate() {
        assertEquals("part-2", tocFragment("text/ch1.xhtml#part-2"))
        assertNull(tocFragment("text/ch1.xhtml"))
        assertNull(tocFragment("text/ch1.xhtml#"))
    }

    // Current location

    @Test fun matchesEntryBehindFragmentHref() {
        val entries = toc(
            Triple("Text/ch1.xhtml#start", "One", 0),
            Triple("Text/ch2.xhtml", "Two", 0),
        )
        val location = findTocLocation(entries, href = "Text/ch1.xhtml", progression = 0.2)
        assertEquals("One", location?.item?.title)
    }

    @Test fun exactFragmentMatchWinsInsideSharedResource() {
        val entries = toc(
            Triple("ch.xhtml#a", "First", 0),
            Triple("ch.xhtml#b", "Second", 0),
            Triple("ch.xhtml#c", "Third", 0),
        )
        assertEquals("Second", findTocLocation(entries, "ch.xhtml#b", 0.9)?.item?.title)
    }

    @Test fun spreadsSharedResourceEntriesByProgression() {
        val entries = toc(
            Triple("ch.xhtml", "Start", 0),
            Triple("ch.xhtml#one", "One", 0),
            Triple("ch.xhtml#two", "Two", 0),
        )
        assertEquals("Start", findTocLocation(entries, "ch.xhtml", 0.0)?.item?.title)
        assertEquals("Two", findTocLocation(entries, "ch.xhtml", 0.9)?.item?.title)
    }

    @Test fun fallsBackToPrecedingEntryWhenResourceNotInToc() {
        val entries = toc(
            Triple("one.xhtml", "One", 0),
            Triple("three.xhtml", "Three", 0),
        )
        val order = listOf("one.xhtml", "two.xhtml", "three.xhtml")
        assertEquals("One", findTocLocation(entries, "two.xhtml", 0.5, order)?.item?.title)
        assertNull(findTocLocation(entries, "one.xhtml", 0.5, order)?.let { if (it.item.title == "Three") it else null })
    }

    @Test fun unknownLocationReturnsNothingRatherThanAPlaceholder() {
        val entries = toc(Triple("one.xhtml", "One", 0))
        assertNull(findTocLocation(entries, null, 0.5))
        assertNull(findTocLocation(emptyList(), "one.xhtml", 0.5))
    }

    // Tree building

    @Test fun groupsFoldAndCountDescendantChapters() {
        val entries = toc(
            Triple("p1.xhtml", "Part One", 0),
            Triple("c1.xhtml", "One", 1),
            Triple("c2.xhtml", "Two", 1),
            Triple("c3.xhtml", "Three", 1),
            Triple("end.xhtml", "Epilogue", 0),
        )
        val rows = buildContentsRows(entries, expandedGroups = emptySet(), currentFlatIndex = 2)
        assertEquals(2, rows.size)
        val group = rows[0] as ContentsGroupRow
        assertEquals(3, group.chapterCount)
        assertFalse(group.isExpanded)
        assertTrue(group.containsCurrent)

        val expanded = buildContentsRows(entries, expandedGroups = setOf(0), currentFlatIndex = 2)
        assertEquals(5, expanded.size)
        val current = expanded.filterIsInstance<ContentsChapterRow>().single { it.isCurrent }
        assertEquals("Two", current.item.title)
    }

    @Test fun flatTocIsPlainRowsWithoutGroups() {
        val entries = toc(
            Triple("c1.xhtml", "One", 0),
            Triple("c2.xhtml", "Two", 0),
        )
        val rows = buildContentsRows(entries, expandedGroups = emptySet(), currentFlatIndex = null)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is ContentsChapterRow && it.depth == 0 })
    }

    @Test fun largeTocOpensWithOnlyTheCurrentGroupExpanded() {
        val entries = buildList {
            for (part in 0 until 3) {
                add(TocItemUiModel("p$part.xhtml", "Part $part", 0))
                for (chapter in 0 until 10) {
                    add(TocItemUiModel("p${part}c$chapter.xhtml", "Chapter $chapter", 1))
                }
            }
        }
        assertTrue(entries.size > LARGE_TOC_THRESHOLD)
        // Current chapter lives in Part One (indices 11..20).
        val expanded = defaultExpandedGroups(entries, currentFlatIndex = 15)
        assertEquals(setOf(11), expanded)
        // Small TOCs expand everything.
        val small = toc(
            Triple("p.xhtml", "Part", 0),
            Triple("c.xhtml", "Chapter", 1),
        )
        assertEquals(setOf(0), defaultExpandedGroups(small, currentFlatIndex = null))
    }

    @Test fun currentGroupAncestorsAreAlwaysKnown() {
        val entries = toc(
            Triple("p.xhtml", "Part", 0),
            Triple("i.xhtml", "Interludes", 1),
            Triple("i1.xhtml", "First", 2),
            Triple("c.xhtml", "Chapter", 1),
        )
        assertEquals(setOf(0, 1), tocAncestors(entries, flatIndex = 2))
        assertEquals(setOf(0), tocAncestors(entries, flatIndex = 3))
        assertEquals(emptySet(), tocAncestors(entries, flatIndex = 0))
    }

    @Test fun collapsedCurrentGroupMarksWhereYouAre() {
        val entries = toc(
            Triple("p1.xhtml", "Part One", 0),
            Triple("c1.xhtml", "One", 1),
            Triple("c2.xhtml", "Two", 1),
        )
        val collapsed = buildContentsRows(entries, expandedGroups = emptySet(), currentFlatIndex = 2)
        val group = collapsed[0] as ContentsGroupRow
        assertTrue(group.containsCurrent)
        assertTrue(group.hidesCurrent)

        val expanded = buildContentsRows(entries, expandedGroups = setOf(0), currentFlatIndex = 2)
        assertFalse((expanded[0] as ContentsGroupRow).hidesCurrent)
        assertTrue(expanded.filterIsInstance<ContentsChapterRow>().any { it.isCurrent })
    }

    @Test fun markSitsOnDeepestVisibleGroupWhenNestedFoldsHideCurrent() {
        val entries = toc(
            Triple("a.xhtml", "A", 0),
            Triple("a1.xhtml", "A intro", 1),
            Triple("b.xhtml", "B", 1),
            Triple("b1.xhtml", "B intro", 2),
            Triple("c.xhtml", "C", 2),
        )
        // A expanded but B (the current chapter's parent) collapsed: B is marked.
        var rows = buildContentsRows(entries, expandedGroups = setOf(0), currentFlatIndex = 4)
        assertFalse((rows.first { it.key == "group-0" } as ContentsGroupRow).hidesCurrent)
        assertTrue((rows.first { it.key == "group-2" } as ContentsGroupRow).hidesCurrent)
        // A collapsed too: only A is visible, so A is marked instead.
        rows = buildContentsRows(entries, expandedGroups = emptySet(), currentFlatIndex = 4)
        assertTrue((rows.first { it.key == "group-0" } as ContentsGroupRow).hidesCurrent)
        // Everything expanded: the card is visible and no group claims the marker.
        rows = buildContentsRows(entries, expandedGroups = setOf(0, 2), currentFlatIndex = 4)
        assertFalse((rows.filterIsInstance<ContentsGroupRow>().any { it.hidesCurrent }))
    }

    @Test fun flatTocNeedsNoHereMarker() {
        val entries = toc(
            Triple("c1.xhtml", "One", 0),
            Triple("c2.xhtml", "Two", 0),
        )
        val rows = buildContentsRows(entries, expandedGroups = emptySet(), currentFlatIndex = 1)
        assertTrue(rows.filterIsInstance<ContentsChapterRow>().any { it.isCurrent })
    }

    // Filtering

    @Test fun filtersCaseAndAccentInsensitivelyWithMatchRanges() {
        val entries = toc(
            Triple("p.xhtml", "Part One", 0),
            Triple("c1.xhtml", "The Bridge at Dusk", 1),
            Triple("c2.xhtml", "Bridges", 1),
            Triple("c3.xhtml", "Nightfall", 1),
        )
        val matches = filterTocByTitle(entries, "bridge")
        assertEquals(listOf("The Bridge at Dusk", "Bridges"), matches.map { it.item.title })
        assertEquals(4, matches[0].matchRange?.first)
        assertEquals(0, matches[0].parentFlatIndex)

        val accented = filterTocByTitle(
            toc(Triple("c.xhtml", "Église", 0)),
            "eglise",
        )
        assertEquals(1, accented.size)
        assertEquals(0, accented[0].matchRange?.first)
    }

    @Test fun blankQueryMatchesNothing() {
        assertTrue(filterTocByTitle(toc(Triple("c.xhtml", "Chapter", 0)), "   ").isEmpty())
    }

    // Bookmark duplicate detection

    @Test fun positionlessBookmarksCompareByProgressionInsteadOfCollapsing() {
        val first = bookmark("ch.xhtml", position = null, progression = 0.1)
        val second = bookmark("ch.xhtml", position = null, progression = 0.7)
        assertFalse(bookmarkMatchesPosition(first, position("ch.xhtml", progression = 0.7)))
        assertTrue(bookmarkMatchesPosition(second, position("ch.xhtml", progression = 0.7)))
    }

    @Test fun locatorPositionsStillMatchExactly() {
        val saved = bookmark("ch.xhtml", position = 42, progression = 0.5)
        assertTrue(bookmarkMatchesPosition(saved, position("ch.xhtml", progression = 0.9, position = 42)))
        assertFalse(bookmarkMatchesPosition(saved, position("ch.xhtml", progression = 0.5, position = 43)))
        assertFalse(bookmarkMatchesPosition(saved, position("other.xhtml", progression = 0.5, position = 42)))
    }

    @Test fun fragmentDifferencesDoNotHideASamePlaceMatch() {
        val saved = bookmark("ch.xhtml#part", position = 1, progression = 0.2)
        assertTrue(bookmarkMatchesPosition(saved, position("ch.xhtml", progression = 0.2, position = 1)))
    }
}
