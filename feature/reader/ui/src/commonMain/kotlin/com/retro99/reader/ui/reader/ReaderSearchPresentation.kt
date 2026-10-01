package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.TocItemUiModel
import kotlinx.serialization.Serializable

const val SEARCH_RESULT_LIMIT = 500

@Serializable
data class RecentBookSearch(val query: String, val count: Int, val complete: Boolean)

data class SearchChapter(val key: String, val number: Int?, val title: String?, val beginning: Boolean = false)
data class SearchHit(val result: ReaderSearchResult, val chapter: SearchChapter)

/** TOC boundaries resolved by the platform; a fragment is not an ordered coordinate by itself. */
data class SearchChapterBoundary(val href: String, val progression: Double?)

fun flattenSearchToc(toc: List<TocItemUiModel>): List<TocItemUiModel> = buildList {
    fun visit(items: List<TocItemUiModel>) {
        items.forEach { item -> add(item); visit(item.children) }
    }
    visit(toc)
}.distinctBy { it.href }

fun searchResource(href: String): String = href.substringBefore('#').removePrefix("./").removePrefix("/")

fun resolveSearchChapter(
    result: ReaderSearchResult,
    toc: List<TocItemUiModel>,
    readingOrder: List<String>,
    boundaries: List<SearchChapterBoundary> = emptyList(),
): SearchChapter = SearchChapterResolver(toc, readingOrder, boundaries).resolve(result)

/** Build once per search, not once per hit. Long books can have thousands of TOC entries. */
class SearchChapterResolver(toc: List<TocItemUiModel>, readingOrder: List<String>, boundaries: List<SearchChapterBoundary>) {
    private val entries = flattenSearchToc(toc)
    private val resourceIndices = readingOrder.mapIndexed { index, href -> searchResource(href) to index }.toMap()
    private val byResource = entries.indices.groupBy { searchResource(entries[it].href) }
    private val byHref = entries.indices.associateBy { entries[it].href }
    private val starts = boundaries.associate { it.href to it.progression }
    private val preceding = run {
        val chapters = entries.indices.mapNotNull { index ->
            resourceIndices[searchResource(entries[index].href)]?.let { it to index }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        var next = 0
        var previous: Int? = null
        List(readingOrder.size) { resourceIndex ->
            while (next < chapters.size && chapters[next].first < resourceIndex) previous = chapters[next++].second
            previous
        }
    }
    private val firstTocResource = entries.firstOrNull()?.let { resourceIndices[searchResource(it.href)] }

    fun resolve(result: ReaderSearchResult): SearchChapter {
        val resource = searchResource(result.href)
        val resourceIndex = resourceIndices[resource]
        val same = byResource[resource].orEmpty()
        val exact = if ('#' in result.href) byHref[result.href] else null
        val within = same.lastOrNull { index ->
            val href = entries[index].href
            val start = starts[href] ?: if ('#' !in href) 0.0 else null
            start != null && result.progression != null && start <= result.progression
        }
        val unresolved = same.singleOrNull()?.takeIf { starts[entries[it].href] == null }
        val index = exact ?: within ?: unresolved ?: resourceIndex?.let { preceding.getOrNull(it) }
        if (index != null) {
            val item = entries[index]
            val title = item.title.trim().takeIf {
                it.isNotEmpty() && it != item.href && it != searchResource(item.href)
            }
            return SearchChapter(item.href, index + 1, title)
        }
        val beginning = resourceIndex != null && (firstTocResource?.let { resourceIndex <= it } ?: (resourceIndex == 0))
        return SearchChapter(if (beginning) "beginning" else "other", null, null, beginning)
    }
}

fun presentSearchHits(results: List<ReaderSearchResult>, toc: List<TocItemUiModel>, readingOrder: List<String>,
    boundaries: List<SearchChapterBoundary> = emptyList()): List<SearchHit> {
    val resolver = SearchChapterResolver(toc, readingOrder, boundaries)
    return results.sortedBy { it.index }.map { SearchHit(it, resolver.resolve(it)) }
}

/** Null means insufficient comparable location data, not the start of the book. */
fun searchHitIsBefore(result: ReaderSearchResult, position: PositionUiModel, readingOrder: List<String>): Boolean? =
    searchHitIsBefore(result, position, readingOrder.mapIndexed { index, href -> searchResource(href) to index }.toMap())

private fun searchHitIsBefore(result: ReaderSearchResult, position: PositionUiModel, resourceIndices: Map<String, Int>): Boolean? {
    if (result.totalProgression != null && position.totalProgression != null) {
        return result.totalProgression < position.totalProgression
    }
    val resultResource = searchResource(result.href)
    val currentResource = searchResource(position.href)
    if (resultResource == currentResource && result.progression != null && position.progression != null) {
        return result.progression < position.progression
    }
    val left = resourceIndices[resultResource]
    val right = resourceIndices[currentResource]
    return if (left != null && right != null && left != right) left < right else null
}

fun searchPositionSplit(results: List<ReaderSearchResult>, position: PositionUiModel?, readingOrder: List<String>): Int? {
    if (position == null || results.isEmpty()) return null
    val indices = readingOrder.mapIndexed { index, href -> searchResource(href) to index }.toMap()
    val comparisons = results.map { searchHitIsBefore(it, position, indices) }
    if (comparisons.any { it == null }) return null
    return comparisons.indexOfFirst { it == false }.takeIf { it >= 0 } ?: results.size
}
