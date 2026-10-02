package com.retro99.reader.domain.recap

/**
 * Bounded read-text buffer. Appends in reading order and, on overflow,
 * drops the oldest text so the most recent [maxChars] remain, starting
 * at a sentence or word boundary. Not thread-safe; callers serialise.
 */
class RecapExcerptBuffer(
    private val maxChars: Int = RecapLimits.MAX_EXCERPT_CHARS,
    initial: String? = null,
) {
    private val builder = StringBuilder()
    private var lastSegment: String? = null

    init {
        require(maxChars > 0) { "maxChars must be positive" }
        initial?.let { builder.append(it.take(maxChars)) }
    }

    val length: Int get() = builder.length

    fun isEmpty(): Boolean = builder.isEmpty()

    /** Returns false when [text] was blank or repeated the previous segment. */
    fun append(text: String): Boolean {
        val segment = text.normalizeSpaces()
        if (segment.isEmpty() || segment == lastSegment) return false
        lastSegment = segment
        if (builder.isNotEmpty()) builder.append(SEPARATOR)
        builder.append(segment)
        if (builder.length > maxChars) trimOldest()
        return true
    }

    fun text(): String = builder.toString()

    private fun trimOldest() {
        val overflow = builder.length - maxChars
        val cut = boundaryAfter(overflow)
        builder.deleteRange(0, cut)
        // A single segment longer than the cap: keep its tail.
        if (builder.length > maxChars) builder.deleteRange(0, builder.length - maxChars)
        while (builder.isNotEmpty() && builder[0].isWhitespace()) builder.deleteAt(0)
    }

    /** The first sentence start (else word start) at or after [from]. */
    private fun boundaryAfter(from: Int): Int {
        val limit = minOf(builder.length, from + BOUNDARY_SEARCH_CHARS)
        for (i in from until limit) {
            if (i > 0 && builder[i - 1] in SENTENCE_ENDS && builder[i].isWhitespace()) return i
        }
        for (i in from until limit) {
            if (builder[i].isWhitespace()) return i
        }
        return from
    }

    private fun String.normalizeSpaces(): String =
        trim().replace(WHITESPACE_RUN, " ")

    private companion object {
        const val SEPARATOR = "\n"
        const val BOUNDARY_SEARCH_CHARS = 400
        val SENTENCE_ENDS = setOf('.', '!', '?', '…', '"', '”', '»')
        val WHITESPACE_RUN = Regex("[ \\t\\u00A0]+")
    }
}
