package com.retro99.reader.domain.translate

import com.retro99.epub.api.EpubChapterText
import com.retro99.server.api.TextAnchor
import kotlin.math.abs
import kotlin.math.max

/** A place in a copy's text: a reading-order chapter and a character offset in its text. */
data class TextPoint(val chapterIndex: Int, val offset: Int)

/**
 * Finds a text anchor in another copy's chapters (§1.5, strategy 2). Returns a match only when
 * it's reliable enough to be High; otherwise null, and the caller falls through.
 */
class TextAnchorMatcher {

    /** How many times [match] ran; tests use it to prove direct mappings skip the matcher. */
    var matchCount: Int = 0
        private set

    fun match(
        anchor: TextAnchor,
        chapters: List<EpubChapterText>,
        estimatedProgression: Double?,
    ): TextPoint? {
        matchCount++
        if (chapters.isEmpty()) return null
        val needle = TextNormalizer.normalizeString(anchor.after).take(SEARCH_CHARS)
        if (needle.length < MIN_NEEDLE_CHARS) return null
        val normalized = chapters.map { chapter -> TextNormalizer.normalize(chapter.text) }

        val hits = normalized.flatMapIndexed { chapterIndex, text ->
            text.text.occurrences(needle).map { index -> chapterIndex to index }
        }
        return when {
            hits.size == 1 -> hits.single().toPoint(normalized)
            hits.size > 1 -> disambiguate(anchor, hits, normalized)
            else -> fuzzy(needle, chapters, normalized, estimatedProgression)
        }
    }

    /** Several exact hits: keep the one whose preceding text best matches `before`. */
    private fun disambiguate(
        anchor: TextAnchor,
        hits: List<Pair<Int, Int>>,
        normalized: List<NormalizedText>,
    ): TextPoint? {
        val before = TextNormalizer.normalizeString(anchor.before).takeLast(BEFORE_CHARS)
        if (before.isEmpty()) return null
        val scored = hits.map { (chapterIndex, index) ->
            val preceding = precedingText(normalized, chapterIndex, index, before.length)
            (chapterIndex to index) to similarity(before, preceding)
        }.sortedByDescending { (_, score) -> score }
        val best = scored[0].second
        val second = scored[1].second
        return if (best >= DISAMBIGUATION_MIN && best - second >= DISAMBIGUATION_LEAD) {
            scored[0].first.toPoint(normalized)
        } else {
            null
        }
    }

    private fun precedingText(
        normalized: List<NormalizedText>,
        chapterIndex: Int,
        index: Int,
        length: Int,
    ): String {
        var text = normalized[chapterIndex].text.substring(0, index).trimEnd()
        var previous = chapterIndex - 1
        while (text.length < length && previous >= 0) {
            text = normalized[previous].text + " " + text
            previous--
        }
        return text.takeLast(length)
    }

    /**
     * No exact hit: slide over the chapters within ±10% of the proportional estimate and keep
     * the best approximate match of the first 60 characters.
     */
    private fun fuzzy(
        needle: String,
        chapters: List<EpubChapterText>,
        normalized: List<NormalizedText>,
        estimatedProgression: Double?,
    ): TextPoint? {
        val pattern = needle.take(FUZZY_CHARS)
        val candidates = candidateChapters(chapters, estimatedProgression)
        var bestDistance = Int.MAX_VALUE
        var bestHit: Pair<Int, Int>? = null
        candidates.forEach { chapterIndex ->
            val text = normalized[chapterIndex].text
            val (distance, end) = approximateSearch(pattern, text)
            if (distance < bestDistance && end >= 0) {
                bestDistance = distance
                bestHit = chapterIndex to matchStart(pattern, text, end, distance)
            }
        }
        val hit = bestHit ?: return null
        val score = 100.0 * (1.0 - bestDistance.toDouble() / pattern.length)
        return if (score >= FUZZY_MIN) hit.toPoint(normalized) else null
    }

    /**
     * Where the approximate match ending at [end] starts. Insertions and deletions shift it by
     * up to [distance] characters, so try each start in that range.
     */
    private fun matchStart(pattern: String, text: String, end: Int, distance: Int): Int {
        val estimate = end - pattern.length + 1
        return ((estimate - distance)..(estimate + distance))
            .filter { start -> start in 0..end }
            .minByOrNull { start -> levenshtein(pattern, text.substring(start, end + 1)) }
            ?: estimate.coerceAtLeast(0)
    }

    private fun candidateChapters(
        chapters: List<EpubChapterText>,
        estimatedProgression: Double?,
    ): List<Int> {
        if (estimatedProgression == null) return chapters.indices.toList()
        val total = chapters.sumOf { chapter -> chapter.text.length }.coerceAtLeast(1)
        val low = estimatedProgression - FUZZY_WINDOW
        val high = estimatedProgression + FUZZY_WINDOW
        var start = 0
        return chapters.indices.filter { index ->
            val chapterStart = start.toDouble() / total
            start += chapters[index].text.length
            val chapterEnd = start.toDouble() / total
            chapterEnd >= low && chapterStart <= high
        }
    }

    private fun Pair<Int, Int>.toPoint(normalized: List<NormalizedText>): TextPoint =
        TextPoint(chapterIndex = first, offset = normalized[first].originalIndex(second))

    companion object {
        const val SEARCH_CHARS = 200
        const val FUZZY_CHARS = 60
        const val BEFORE_CHARS = 120
        const val MIN_NEEDLE_CHARS = 12
        const val DISAMBIGUATION_MIN = 90.0
        const val DISAMBIGUATION_LEAD = 10.0
        const val FUZZY_MIN = 92.0
        const val FUZZY_WINDOW = 0.10
    }
}

private fun String.occurrences(needle: String): List<Int> {
    val result = mutableListOf<Int>()
    var index = indexOf(needle)
    while (index >= 0) {
        result += index
        index = indexOf(needle, index + 1)
    }
    return result
}

/** Levenshtein similarity from 0 to 100. */
internal fun similarity(first: String, second: String): Double {
    val longest = max(first.length, second.length)
    if (longest == 0) return 100.0
    return 100.0 * (1.0 - levenshtein(first, second).toDouble() / longest)
}

internal fun levenshtein(first: String, second: String): Int {
    if (first == second) return 0
    var previous = IntArray(second.length + 1) { index -> index }
    var current = IntArray(second.length + 1)
    for (i in 1..first.length) {
        current[0] = i
        for (j in 1..second.length) {
            val cost = if (first[i - 1] == second[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length]
}

/**
 * The smallest edit distance between [pattern] (at most 64 characters) and any substring of
 * [text], and the index where that substring ends. Myers' bit-parallel algorithm, O(n).
 */
internal fun approximateSearch(pattern: String, text: String): Pair<Int, Int> {
    val length = pattern.length
    require(length in 1..64) { "pattern must be 1..64 characters" }
    val masks = HashMap<Char, Long>()
    pattern.forEachIndexed { index, char ->
        masks[char] = (masks[char] ?: 0L) or (1L shl index)
    }
    val highBit = 1L shl (length - 1)
    var positive = if (length == 64) -1L else (1L shl length) - 1
    var negative = 0L
    var score = length
    var bestScore = length
    var bestEnd = -1
    text.forEachIndexed { index, char ->
        val equal = masks[char] ?: 0L
        val xv = equal or negative
        val xh = (((equal and positive) + positive) xor positive) or equal
        var horizontalPositive = negative or (xh or positive).inv()
        var horizontalNegative = positive and xh
        if (horizontalPositive and highBit != 0L) score++
        else if (horizontalNegative and highBit != 0L) score--
        horizontalPositive = horizontalPositive shl 1
        horizontalNegative = horizontalNegative shl 1
        positive = horizontalNegative or (xv or horizontalPositive).inv()
        negative = horizontalPositive and xv
        if (score < bestScore) {
            bestScore = score
            bestEnd = index
        }
    }
    return bestScore to bestEnd
}

/** True when two durations differ by at most 1% of the longer (P6b). */
internal fun durationsMatch(first: Long, second: Long): Boolean {
    if (first <= 0 || second <= 0) return false
    return abs(first - second) <= max(first, second) * DURATION_TOLERANCE
}

private const val DURATION_TOLERANCE = 0.01
