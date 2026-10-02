package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.TocItemUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderSearchPresentationTest {
    private val order = listOf("front.xhtml", "one.xhtml", "two.xhtml", "three.xhtml")
    private fun hit(href: String, index: Int = 0, progression: Double? = 0.5, total: Double? = null) =
        ReaderSearchResult(href, "application/xhtml+xml", null, progression, null, total,
            "some", "know", "ledge", index, "{}")
    private fun mark(href: String = "one.xhtml", progression: Double? = null, total: Double? = null) =
        SearchBoundaryMark(href, progression, total)

    // Chapter resolution

    @Test fun resolvesFragmentsByBoundaryNotAlphabeticalFragmentName() {
        val toc = listOf(TocItemUiModel("one.xhtml#z", "First"), TocItemUiModel("one.xhtml#a", "Second"))
        val boundaries = listOf(SearchChapterBoundary("one.xhtml#z", 0.0), SearchChapterBoundary("one.xhtml#a", 0.6))
        assertEquals("First", resolveSearchChapter(hit("one.xhtml", progression = 0.3), toc, order, boundaries).title)
        assertEquals("Second", resolveSearchChapter(hit("one.xhtml", progression = 0.8), toc, order, boundaries).title)
    }

    @Test fun untitledResourceUsesNearestPrecedingTocEntry() {
        val toc = listOf(TocItemUiModel("one.xhtml#start", "Chapter"), TocItemUiModel("three.xhtml", "Later"))
        assertEquals("Chapter", resolveSearchChapter(hit("two.xhtml"), toc, order).title)
        assertTrue(resolveSearchChapter(hit("front.xhtml"), toc, order).beginning)
    }

    @Test fun blankTitlesDoNotBecomeNumberedResultLabels() {
        val chapter = resolveSearchChapter(hit("one.xhtml"), listOf(TocItemUiModel("one.xhtml", " ")), order)
        assertNull(chapter.title)
        assertEquals("one.xhtml", chapter.key)
        assertNull(resolveSearchChapter(hit("one.xhtml"), listOf(TocItemUiModel("one.xhtml", "one.xhtml")), order).title)
    }

    @Test fun sameTitleDoesNotMergeDifferentChapters() {
        val toc = listOf(TocItemUiModel("one.xhtml", "Same"), TocItemUiModel("two.xhtml", "Same"))
        val hits = presentSearchHits(listOf(hit("two.xhtml", 1), hit("one.xhtml", 0)), toc, order)
        assertEquals(listOf(0, 1), hits.map { it.result.index })
        assertEquals(2, hits.groupBy { it.chapter.key }.size)
    }

    @Test fun matchBoundariesAreNotFlattened() {
        val result = hit("one.xhtml")
        assertEquals("someknowledge", result.before + result.match + result.after)
    }

    @Test fun resourceLevelTocEntryDoesNotHideLaterFragmentChapter() {
        val toc = listOf(TocItemUiModel("one.xhtml", "First"), TocItemUiModel("one.xhtml#later", "Second"))
        val boundaries = listOf(SearchChapterBoundary("one.xhtml#later", 0.6))
        assertEquals("Second", resolveSearchChapter(hit("one.xhtml", progression = 0.8), toc, order, boundaries).title)
    }

    @Test fun largeBookUsesOnePrecomputedResolverForAllHits() {
        val resources = (0 until 10_000).map { "chapter-$it.xhtml" }
        val toc = resources.mapIndexed { index, href -> TocItemUiModel(href, "Chapter $index") }
        val resolver = SearchChapterResolver(toc, resources, emptyList())
        resources.forEachIndexed { index, href ->
            assertEquals("Chapter $index", resolver.resolve(hit(href, index)).title)
        }
    }

    // Spoiler boundary

    @Test fun boundaryIsTheFurthestPointReachedNotTheCurrentPage() {
        assertEquals(0.7, resolveSearchBoundary(mark(total = 0.7), mark(total = 0.4))?.totalProgression)
    }

    @Test fun boundaryFallsBackToTheCurrentPosition() {
        assertEquals(0.4, resolveSearchBoundary(null, mark(total = 0.4))?.totalProgression)
        assertNull(resolveSearchBoundary(null, null))
    }

    @Test fun finishedBooksHaveNoBoundary() {
        assertNull(resolveSearchBoundary(mark(total = 0.99), mark(total = 0.5)))
        assertNull(resolveSearchBoundary(mark(total = 0.5), mark(total = 0.98)))
    }

    @Test fun boundaryWithoutAnyProgressionHidesNothing() {
        assertNull(resolveSearchBoundary(mark(total = null), mark(total = null)))
    }

    @Test fun ordersMatchesByTotalProgression() {
        val boundary = mark(total = 0.5)
        assertEquals(true, searchHitIsBefore(hit("one.xhtml", total = 0.4), boundary, order))
        assertEquals(false, searchHitIsBefore(hit("one.xhtml", total = 0.5), boundary, order))
        assertEquals(false, searchHitIsBefore(hit("one.xhtml", total = 0.6), boundary, order))
    }

    @Test fun fallsBackToProgressionWithinOneResource() {
        val boundary = mark(href = "one.xhtml", progression = 0.5)
        assertEquals(true, searchHitIsBefore(hit("one.xhtml", 0, 0.2), boundary, order))
        assertEquals(false, searchHitIsBefore(hit("one.xhtml", 1, 0.8), boundary, order))
    }

    @Test fun fallsBackToReadingOrderAcrossResources() {
        val boundary = mark(href = "two.xhtml", total = null)
        assertEquals(true, searchHitIsBefore(hit("one.xhtml"), boundary, order))
        assertEquals(false, searchHitIsBefore(hit("three.xhtml"), boundary, order))
    }

    @Test fun missingBookProgressionFallsBackToResourceThenLocalProgression() {
        assertEquals(true, searchHitIsBefore(hit("one.xhtml", 0, 0.2), mark(progression = 0.5), order))
        assertNull(searchHitIsBefore(hit("one.xhtml", progression = null), mark(progression = null), order))
        assertNull(searchHitIsBefore(hit("unknown.xhtml"), mark(), order))
    }

    @Test fun unorderedMatchesStayHidden() {
        assertNull(searchHitIsBefore(hit("unknown.xhtml"), mark(total = 0.5), order))
    }

    // Scan splitting

    @Test fun withoutABoundaryEverythingIsKept() {
        val hits = listOf(hit("one.xhtml", 0, total = 0.1), hit("three.xhtml", 1, total = 0.9))
        val split = splitAtBoundary(hits, boundary = null, readingOrder = order)
        assertEquals(hits, split.kept)
        assertFalse(split.passedBoundary)
    }

    @Test fun splitUsesBookProgressionAndKeepsEqualHitAfterDivider() {
        val hits = listOf(hit("one.xhtml", 0, total = 0.1), hit("two.xhtml", 1, total = 0.34), hit("three.xhtml", 2, total = 0.8))
        val split = splitAtBoundary(hits, mark(total = 0.34), order)
        assertEquals(listOf(0), split.kept.map { it.index })
        assertTrue(split.passedBoundary)
    }

    @Test fun scanStopsAtTheBoundaryAndDropsWhatComesAfter() {
        val hits = listOf(hit("one.xhtml", 0, total = 0.1), hit("two.xhtml", 1, total = 0.5), hit("three.xhtml", 2, total = 0.9))
        val split = splitAtBoundary(hits, mark(total = 0.5), order)
        assertEquals(listOf(0), split.kept.map { it.index })
        assertTrue(split.passedBoundary)
    }

    @Test fun unorderableMatchesStayHiddenWithoutStoppingTheScan() {
        val hits = listOf(hit("unknown.xhtml", 0), hit("one.xhtml", 1, total = 0.1))
        val split = splitAtBoundary(hits, mark(total = 0.5), order)
        assertEquals(listOf(1), split.kept.map { it.index })
        assertFalse(split.passedBoundary)
    }
}
