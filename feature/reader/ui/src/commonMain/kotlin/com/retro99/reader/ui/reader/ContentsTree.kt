package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.TocItemUiModel
import kotlin.math.abs

/**
 * Pure model behind the Contents sheet: href normalisation, current-location resolution,
 * foldable tree rows and title filtering. No Compose, so it stays unit-testable.
 *
 * The tree is always rebuilt from the flat preorder TOC plus [TocItemUiModel.level]
 * via [toTocNodes]; the platform `children` fields differ between Android (flattened
 * descendants) and iOS (empty) and must not be trusted.
 */

/** Groups with at most this many chapters show a bare count instead of "N chapters". */
internal const val SMALL_GROUP_MAX = 5

/** TOCs larger than this open with only the current group expanded. */
internal const val LARGE_TOC_THRESHOLD = 20

/**
 * Comparison form of an href: fragment and query dropped, percent-escapes decoded,
 * `.`/`..`/empty segments resolved. Leading `..` segments are dropped, so paths that
 * differ only in an unresolvable relative prefix still match.
 */
internal fun normaliseTocHref(href: String): String {
    val path = decodePercentEscapes(href.substringBefore('#').substringBefore('?'))
    val resolved = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            else -> resolved.add(segment)
        }
    }
    return resolved.joinToString("/")
}

/** Fragment of an href without the `#`, or null when the href points at a whole resource. */
internal fun tocFragment(href: String): String? =
    href.substringAfter('#', "").takeIf { it.isNotEmpty() }

private fun decodePercentEscapes(text: String): String {
    if ('%' !in text) return text
    val bytes = ArrayList<Byte>(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '%' && index + 2 < text.length) {
            val high = hexValue(text[index + 1])
            val low = hexValue(text[index + 2])
            if (high != null && low != null) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        for (byte in char.toString().encodeToByteArray()) bytes.add(byte)
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexValue(char: Char): Int? = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> null
}

/**
 * Case- and accent-insensitive form of [this] for matching. Length preserving (one folded
 * char per source char) so match ranges map straight back onto the original title.
 */
internal fun String.foldForMatch(): String = buildString(length) {
    for (char in this@foldForMatch) append(DIACRITIC_FOLD[char.lowercaseChar()] ?: char.lowercaseChar())
}

private val DIACRITIC_FOLD: Map<Char, Char> = buildMap {
    fun bind(chars: String, to: Char) = chars.forEach { put(it, to) }
    bind("ÀÁÂÃÄÅĀĂĄǍǠǺȀȂȦ", 'A')
    bind("àáâãäåāăąǎǡǻȁȃȧ", 'a')
    bind("Æ", 'A')
    bind("æ", 'a')
    bind("ÇĆĈĊČ", 'C')
    bind("çćĉċč", 'c')
    bind("ĎĐÐ", 'D')
    bind("ďđð", 'd')
    bind("ÈÉÊËĒĔĖĘĚȄȆȨ", 'E')
    bind("èéêëēĕėęěȅȇȩ", 'e')
    bind("ĜĞĠĢ", 'G')
    bind("ĝğġģ", 'g')
    bind("ĤĦ", 'H')
    bind("ĥħ", 'h')
    bind("ÌÍÎÏĨĪĬĮİǏ", 'I')
    bind("ìíîïĩīĭįıǐ", 'i')
    bind("Ĵ", 'J')
    bind("ĵ", 'j')
    bind("Ķ", 'K')
    bind("ķ", 'k')
    bind("ĹĻĽĿŁ", 'L')
    bind("ĺļľŀł", 'l')
    bind("ÑŃŅŇŊ", 'N')
    bind("ñńņňŉŋ", 'n')
    bind("ÒÓÔÕÖØŌŎŐǑǪǬǾ", 'O')
    bind("òóôõöøōŏőǒǫǭǿ", 'o')
    bind("Œ", 'O')
    bind("œ", 'o')
    bind("ŔŖŘ", 'R')
    bind("ŕŗř", 'r')
    bind("ŚŜŞŠȘ", 'S')
    bind("śŝşšșß", 's')
    bind("ŢŤŦȚ", 'T')
    bind("ţťŧț", 't')
    bind("ÙÚÛÜŨŪŬŮŰŲǓǕǗǙǛ", 'U')
    bind("ùúûüũūŭůűųǔǖǘǚǜ", 'u')
    bind("Ŵ", 'W')
    bind("ŵ", 'w')
    bind("ÝŶŸ", 'Y')
    bind("ýÿŷ", 'y')
    bind("ŹŻŽ", 'Z')
    bind("źżž", 'z')
}

/** Flat index of each entry's immediate parent, derived from [TocItemUiModel.level]. */
internal fun tocParentIndices(toc: List<TocItemUiModel>): Map<Int, Int> {
    val parents = mutableMapOf<Int, Int>()
    val stack = ArrayDeque<Int>()
    for ((index, item) in toc.withIndex()) {
        while (stack.isNotEmpty() && toc[stack.last()].level >= item.level) stack.removeLast()
        stack.lastOrNull()?.let { parents[index] = it }
        stack.addLast(index)
    }
    return parents
}

/** Number of chapter (childless) entries in each entry's subtree, keyed by flat index. */
internal fun tocDescendantCounts(toc: List<TocItemUiModel>): Map<Int, Int> {
    val parents = tocParentIndices(toc)
    val children = parents.entries.groupBy({ it.value }, { it.key })
    val counts = mutableMapOf<Int, Int>()
    fun count(index: Int): Int {
        counts[index]?.let { return it }
        val kids = children[index].orEmpty()
        val total = if (kids.isEmpty()) 0 else kids.sumOf { child -> if (children[child].isNullOrEmpty()) 1 else count(child) }
        counts[index] = total
        return total
    }
    for (index in toc.indices) count(index)
    return counts
}

internal sealed interface ContentsRow {
    val key: String
}

internal data class ContentsGroupRow(
    val flatIndex: Int,
    val title: String,
    val chapterCount: Int,
    val isExpanded: Boolean,
    val containsCurrent: Boolean,
    /** True for the group that visibly contains the current chapter while folding hides its card. */
    val hidesCurrent: Boolean,
    val depth: Int,
    val href: String,
) : ContentsRow {
    override val key: String = "group-$flatIndex"
}

internal data class ContentsChapterRow(
    val flatIndex: Int,
    val item: TocItemUiModel,
    val depth: Int,
    val isCurrent: Boolean,
    val matchRange: IntRange? = null,
) : ContentsRow {
    override val key: String = "chapter-$flatIndex"
}

/** True when the book has no hierarchy: every entry is a plain chapter row. */
internal fun isFlatToc(toc: List<TocItemUiModel>): Boolean = toc.all { it.level <= 0 }

/**
 * Rows for the Chapters tab. Groups (entries with children) fold; only their direct
 * children re-render at a deeper indent level. [currentFlatIndex] marks the "you're here"
 * card and the group that contains it.
 */
internal fun buildContentsRows(
    toc: List<TocItemUiModel>,
    expandedGroups: Set<Int>,
    currentFlatIndex: Int?,
): List<ContentsRow> {
    if (toc.isEmpty()) return emptyList()
    val parents = tocParentIndices(toc)
    val children = parents.entries.groupBy({ it.value }, { it.key })
    val counts = tocDescendantCounts(toc)
    val hiddenCurrentGroup = hiddenCurrentGroupIndex(parents, expandedGroups, currentFlatIndex)
    val rows = mutableListOf<ContentsRow>()

    fun add(index: Int, depth: Int) {
        val item = toc[index]
        val kids = children[index].orEmpty()
        if (kids.isEmpty()) {
            rows += ContentsChapterRow(
                flatIndex = index,
                item = item,
                depth = depth,
                isCurrent = index == currentFlatIndex,
            )
        } else {
            val isExpanded = index in expandedGroups
            rows += ContentsGroupRow(
                flatIndex = index,
                title = item.title,
                chapterCount = counts[index] ?: 0,
                isExpanded = isExpanded,
                containsCurrent = currentFlatIndex != null && isAncestorOf(parents, currentFlatIndex, index),
                hidesCurrent = index == hiddenCurrentGroup,
                depth = depth,
                href = item.href,
            )
            if (isExpanded) kids.forEach { add(it, depth + 1) }
        }
    }
    for (index in toc.indices) if (index !in parents) add(index, 0)
    return rows
}

/**
 * The group row that should say "you're here": the deepest visible ancestor of the current
 * entry, used exactly when folding hides the current card itself. Null when the card is
 * visible or there is no current entry — one honest marker, never two.
 */
internal fun hiddenCurrentGroupIndex(
    parents: Map<Int, Int>,
    expandedGroups: Set<Int>,
    currentFlatIndex: Int?,
): Int? {
    if (currentFlatIndex == null) return null
    val chain = generateSequence(parents[currentFlatIndex]) { parents[it] }.toList()
    if (chain.isEmpty()) return null
    // The card is visible exactly when every ancestor group is expanded.
    if (chain.all { it in expandedGroups }) return null
    return chain.firstOrNull { candidate ->
        val ancestors = chain.subList(chain.indexOf(candidate) + 1, chain.size)
        ancestors.all { it in expandedGroups }
    }
}

private fun isAncestorOf(parents: Map<Int, Int>, index: Int, ancestor: Int): Boolean {
    var current: Int? = parents[index]
    while (current != null) {
        if (current == ancestor) return true
        current = parents[current]
    }
    return false
}

/** Flat indices of the entry's ancestor groups, outermost first. */
internal fun tocAncestors(toc: List<TocItemUiModel>, flatIndex: Int): Set<Int> {
    val parents = tocParentIndices(toc)
    return generateSequence(parents[flatIndex]) { parents[it] }.toSet()
}

/**
 * Groups expanded when the sheet first opens: everything for small TOCs, otherwise only
 * the ancestors of the current entry.
 */
internal fun defaultExpandedGroups(toc: List<TocItemUiModel>, currentFlatIndex: Int?): Set<Int> {
    if (toc.size <= LARGE_TOC_THRESHOLD) return tocParentIndices(toc).values.toSet()
    if (currentFlatIndex == null) return emptySet()
    return tocAncestors(toc, currentFlatIndex)
}

internal data class TocLocation(val flatIndex: Int, val item: TocItemUiModel)

/**
 * The TOC entry at or before the given position. Hrefs are compared normalised, so
 * fragments and relative prefixes do not hide a match. Within a resource shared by
 * several entries an exact fragment match wins; without one the candidates are spread
 * across the resource by progression (the start of the file is its first entry).
 * When the resource is not in the TOC at all, the last entry in a preceding resource
 * is used, ordered by [readingOrder] when available.
 */
internal fun findTocLocation(
    toc: List<TocItemUiModel>,
    href: String?,
    progression: Double?,
    readingOrder: List<String> = emptyList(),
): TocLocation? {
    if (toc.isEmpty() || href.isNullOrBlank()) return null
    val target = normaliseTocHref(href)
    val fragment = tocFragment(href)
    val candidates = ArrayList<Int>()
    for ((index, entry) in toc.withIndex()) {
        if (normaliseTocHref(entry.href) == target) candidates.add(index)
    }
    if (candidates.isNotEmpty()) {
        if (fragment != null) {
            candidates.lastOrNull { tocFragment(toc[it].href) == fragment }?.let { return TocLocation(it, toc[it]) }
        }
        val fraction = (progression ?: 0.0).coerceIn(0.0, 1.0)
        val chosen = candidates[((fraction * candidates.size).toInt()).coerceAtMost(candidates.size - 1)]
        return TocLocation(chosen, toc[chosen])
    }
    val order = readingOrder.map { normaliseTocHref(it) }
    val targetIndex = order.indexOf(target)
    if (targetIndex <= 0) return null
    for (index in toc.indices.reversed()) {
        val entryIndex = order.indexOf(normaliseTocHref(toc[index].href))
        if (entryIndex in 0 until targetIndex) return TocLocation(index, toc[index])
    }
    return null
}

internal data class TocMatch(
    val flatIndex: Int,
    val item: TocItemUiModel,
    val parentFlatIndex: Int?,
    val matchRange: IntRange?,
)

/** Title matches for "Find a chapter"; case- and accent-insensitive, with the matched range. */
internal fun filterTocByTitle(toc: List<TocItemUiModel>, query: String): List<TocMatch> {
    val needle = query.trim().foldForMatch()
    if (needle.isEmpty()) return emptyList()
    val parents = tocParentIndices(toc)
    return toc.mapIndexedNotNull { index, item ->
        val folded = item.title.foldForMatch()
        val start = folded.indexOf(needle)
        if (start < 0) null
        else TocMatch(index, item, parents[index], start until start + needle.length)
    }
}

/**
 * Whether a bookmark already marks the same place as [position]. Locations without a
 * locator position fall back to (resource) progression, then book progression, so two
 * position-less bookmarks in one resource no longer collapse into one place.
 */
internal fun bookmarkMatchesPosition(bookmark: BookmarkUiModel, position: PositionUiModel): Boolean {
    if (normaliseTocHref(bookmark.locatorHref) != normaliseTocHref(position.href)) return false
    val bookmarkPage = bookmark.position
    val positionPage = position.position
    if (bookmarkPage != null && positionPage != null) return bookmarkPage == positionPage
    val bookmarkProgression = bookmark.progression
    val positionProgression = position.progression
    if (bookmarkProgression != null && positionProgression != null) {
        return abs(bookmarkProgression - positionProgression) < 1e-3
    }
    val bookmarkTotal = bookmark.totalProgression
    val positionTotal = position.totalProgression
    return bookmarkTotal != null && positionTotal != null && abs(bookmarkTotal - positionTotal) < 1e-3
}
