package com.retro99.reader.domain.translate

import com.retro99.epub.api.EpubChapterText
import com.retro99.server.api.TextAnchor

/** Chapter hrefs as locators and EPUB paths name them: decoded, no leading '/', no fragment. */
internal fun normalizeHref(href: String): String =
    percentDecode(href.substringBefore('#')).trimStart('/')

/** The chapter [href] names: the same path, or one path ending with the other. */
internal fun List<EpubChapterText>.indexOfHref(href: String?): Int {
    if (href == null) return -1
    val wanted = normalizeHref(href)
    if (wanted.isEmpty()) return -1
    indexOfFirst { chapter -> normalizeHref(chapter.href) == wanted }
        .takeIf { index -> index >= 0 }
        ?.let { index -> return index }
    return indexOfFirst { chapter ->
        val path = normalizeHref(chapter.href)
        path.endsWith("/$wanted") || wanted.endsWith("/$path")
    }
}

/**
 * Where a locator points in [chapters]: its element's start when the CSS selector names an id,
 * otherwise the progression through the chapter.
 */
internal fun List<EpubChapterText>.pointOf(
    href: String?,
    progression: Double?,
    cssSelector: String?,
): TextPoint? {
    val chapterIndex = indexOfHref(href)
    if (chapterIndex < 0) return null
    val chapter = this[chapterIndex]
    val elementId = cssSelector?.trim()?.takeIf { selector -> selector.startsWith("#") }
        ?.removePrefix("#")
    val element = elementId?.let { id ->
        chapter.elementOffsets.firstOrNull { offset -> offset.elementId == id }
    }
    val offset = element?.startOffset
        ?: ((progression ?: 0.0).coerceIn(0.0, 1.0) * chapter.text.length).toInt()
    return TextPoint(chapterIndex, offset.coerceIn(0, chapter.text.length))
}

/** The anchor around [point]: up to 20 words before and 30 after. */
internal fun List<EpubChapterText>.anchorAt(point: TextPoint): TextAnchor? {
    val text = this[point.chapterIndex].text
    val offset = point.offset.coerceIn(0, text.length)
    return TextAnchor.of(
        before = text.substring((offset - ANCHOR_CONTEXT_CHARS).coerceAtLeast(0), offset),
        after = text.substring(offset, (offset + ANCHOR_CONTEXT_CHARS).coerceAtMost(text.length)),
    )
}

/** The innermost element with an id that contains [point], as a CSS selector. */
internal fun List<EpubChapterText>.cssSelectorAt(point: TextPoint): String? =
    this[point.chapterIndex].elementOffsets
        .filter { element ->
            element.startOffset <= point.offset && point.offset < element.endOffset
        }
        .maxByOrNull { element -> element.startOffset }
        ?.let { element -> "#${element.elementId}" }

/** Progression through the whole book at [point], from the chapters' text lengths. */
internal fun List<EpubChapterText>.totalProgressionAt(point: TextPoint): Double {
    val total = sumOf { chapter -> chapter.text.length }
    if (total == 0) return 0.0
    val before = take(point.chapterIndex).sumOf { chapter -> chapter.text.length }
    return ((before + point.offset).toDouble() / total).coerceIn(0.0, 1.0)
}

internal fun List<EpubChapterText>.progressionAt(point: TextPoint): Double {
    val length = this[point.chapterIndex].text.length
    if (length == 0) return 0.0
    return (point.offset.toDouble() / length).coerceIn(0.0, 1.0)
}

/** The point at [totalProgression] through the book, by text length. */
internal fun List<EpubChapterText>.pointAtTotalProgression(totalProgression: Double): TextPoint? {
    if (isEmpty()) return null
    val total = sumOf { chapter -> chapter.text.length }
    val target = (totalProgression.coerceIn(0.0, 1.0) * total).toInt()
    var start = 0
    forEachIndexed { index, chapter ->
        val end = start + chapter.text.length
        if (target < end || index == lastIndex) {
            return TextPoint(index, (target - start).coerceIn(0, chapter.text.length))
        }
        start = end
    }
    return null
}

private fun percentDecode(input: String): String {
    if ('%' !in input) return input
    val bytes = ArrayList<Byte>(input.length)
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (char == '%' && index + 2 < input.length) {
            val value = input.substring(index + 1, index + 3).toIntOrNull(16)
            if (value != null) {
                bytes.add(value.toByte())
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach { byte -> bytes.add(byte) }
        index++
    }
    return bytes.toByteArray().decodeToString()
}

private const val ANCHOR_CONTEXT_CHARS = 400
