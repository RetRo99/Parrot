package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.TocItemUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderSearchPresentationTest {
    private val order = listOf("front.xhtml", "one.xhtml", "two.xhtml", "three.xhtml")
    private fun hit(href: String, index: Int = 0, progression: Double? = 0.5, total: Double? = null) =
        ReaderSearchResult(href, "application/xhtml+xml", null, progression, null, total,
            "some", "know", "ledge", index, "{}")
    private fun position(href: String = "one.xhtml", progression: Double? = 0.5, total: Double? = null) =
        PositionUiModel(createdAt = "", href = href, type = "application/xhtml+xml", title = null,
            progression = progression, position = null, totalProgression = total, chapterIndex = null, totalChapters = null)

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

    @Test fun splitUsesBookProgressionAndKeepsEqualHitAfterDivider() {
        val hits = listOf(hit("one.xhtml", 0, total = 0.1), hit("two.xhtml", 1, total = 0.34), hit("three.xhtml", 2, total = 0.8))
        assertEquals(1, searchPositionSplit(hits, position(total = 0.34), order))
        assertEquals(0, searchPositionSplit(hits, position(total = 0.0), order))
        assertEquals(3, searchPositionSplit(hits, position(total = 0.9), order))
    }

    @Test fun missingBookProgressionFallsBackToResourceThenLocalProgression() {
        assertEquals(1, searchPositionSplit(listOf(hit("one.xhtml", 0, 0.2), hit("two.xhtml", 1)), position(), order))
        assertNull(searchPositionSplit(listOf(hit("one.xhtml", progression = null)), position(progression = null), order))
        assertNull(searchPositionSplit(listOf(hit("unknown.xhtml")), position(), order))
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
            assertEquals(index + 1, resolver.resolve(hit(href, index)).number)
        }
    }
}
