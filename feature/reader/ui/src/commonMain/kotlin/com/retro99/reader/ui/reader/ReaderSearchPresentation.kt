package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.TocItemUiModel
import kotlinx.serialization.Serializable

const val SEARCH_RESULT_LIMIT = 500

/** At or beyond this book progression the book counts as finished and nothing is hidden. */
const val FINISHED_BOOK_PROGRESSION = 0.98

@Serializable
data class RecentBookSearch(val query: String, val count: Int, val complete: Boolean)

data class SearchChapter(val key: String, val title: String?, val beginning: Boolean = false)
data class SearchHit(val result: ReaderSearchResult, val chapter: SearchChapter)

/** TOC boundaries resolved by the platform; a fragment is not an ordered coordinate by itself. */
data class SearchChapterBoundary(val href: String, val progression: Double?)

/**
 * The furthest place reached in this book: the spoiler boundary for book search.
 * Everything beyond it is hidden until the reader explicitly asks for it.
 */
@Serializable
data class SearchBoundaryMark(val href: String, val progression: Double?, val totalProgression: Double?)

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
            return SearchChapter(item.href, title)
        }
        val beginning = resourceIndex != null && (firstTocResource?.let { resourceIndex <= it } ?: (resourceIndex == 0))
        return SearchChapter(if (beginning) "beginning" else "other", null, beginning)
    }
}

fun presentSearchHits(results: List<ReaderSearchResult>, toc: List<TocItemUiModel>, readingOrder: List<String>,
    boundaries: List<SearchChapterBoundary> = emptyList()): List<SearchHit> {
    val resolver = SearchChapterResolver(toc, readingOrder, boundaries)
    return results.sortedBy { it.index }.map { SearchHit(it, resolver.resolve(it)) }
}

/**
 * Whether the match sits strictly before [boundary] in the book. Null means insufficient
 * comparable location data, not the start of the book; such results stay hidden (spoiler
 * safety wins) without stopping the scan.
 */
fun searchHitIsBefore(result: ReaderSearchResult, boundary: SearchBoundaryMark, readingOrder: List<String>): Boolean? =
    searchHitIsBefore(result, boundary, readingOrder.mapIndexed { index, href -> searchResource(href) to index }.toMap())

private fun searchHitIsBefore(result: ReaderSearchResult, boundary: SearchBoundaryMark, resourceIndices: Map<String, Int>): Boolean? {
    if (result.totalProgression != null && boundary.totalProgression != null) {
        return result.totalProgression < boundary.totalProgression
    }
    val resultResource = searchResource(result.href)
    val boundaryResource = searchResource(boundary.href)
    if (resultResource == boundaryResource && result.progression != null && boundary.progression != null) {
        return result.progression < boundary.progression
    }
    val left = resourceIndices[resultResource]
    val right = resourceIndices[boundaryResource]
    return if (left != null && right != null && left != right) left < right else null
}

/** Results kept before the boundary, and whether the scan has passed it and must stop. */
data class BoundarySplit(val kept: List<ReaderSearchResult>, val passedBoundary: Boolean)

/**
 * Keeps the matches that are verifiably read and stops the scan once it passes [boundary].
 * Without a boundary everything is kept. Results that cannot be ordered against the
 * boundary stay hidden but do not stop the scan.
 */
fun splitAtBoundary(
    results: List<ReaderSearchResult>,
    boundary: SearchBoundaryMark?,
    readingOrder: List<String>,
): BoundarySplit {
    if (boundary == null) return BoundarySplit(results, passedBoundary = false)
    val indices = readingOrder.mapIndexed { index, href -> searchResource(href) to index }.toMap()
    val kept = ArrayList<ReaderSearchResult>(results.size)
    for (result in results) {
        when (searchHitIsBefore(result, boundary, indices)) {
            true -> kept += result
            false -> return BoundarySplit(kept, passedBoundary = true)
            null -> Unit
        }
    }
    return BoundarySplit(kept, passedBoundary = false)
}

/**
 * The spoiler boundary for a search: the furthest point reached in this book, falling back
 * to the current position. Finished books have no boundary — nothing to protect.
 */
fun resolveSearchBoundary(furthest: SearchBoundaryMark?, current: SearchBoundaryMark?): SearchBoundaryMark? {
    val boundary = listOfNotNull(furthest, current).maxByOrNull { it.totalProgression ?: -1.0 } ?: return null
    val total = boundary.totalProgression ?: return null
    return if (total >= FINISHED_BOOK_PROGRESSION) null else boundary
}

/** Book percentage of the boundary, for "up to your page (72%)". */
fun SearchBoundaryMark.percent(): Int = ((totalProgression ?: 0.0) * 100).toInt()
