package com.retro99.reader.domain.recap

/**
 * Bounded read-text buffer: preserves a brief opening and the most recent
 * text verbatim. Not thread-safe; callers serialise. Never summarizes locally.
 */
class RecapExcerptBuffer(initial: String? = null) {
    private val builder = StringBuilder(initial.orEmpty())
    private var lastSegment: String? = null
    private var opening: String? = null

    val length: Int get() = builder.length

    fun isEmpty(): Boolean = builder.isEmpty()

    /** Returns false when [text] was blank or repeated the previous segment. */
    fun append(text: String): Boolean {
        val segment = text.normalizeSpaces()
        if (segment.isEmpty() || segment == lastSegment) return false
        lastSegment = segment
        if (builder.isNotEmpty()) builder.append(SEPARATOR)
        builder.append(segment)
        if (builder.length > RecapLimits.MAX_EXCERPT_CHARS) {
            val opening = opening ?: builder.substring(0, RecapLimits.OPENING_CHARS)
                .substringBeforeLast(' ').also { opening = it }
            val tail = builder.takeLast(RecapLimits.MAX_EXCERPT_CHARS - opening.length - SEPARATOR.length)
                .toString().substringAfter(' ')
            builder.clear()
            builder.append(opening).append(SEPARATOR).append(tail)
        }
        return true
    }

    fun text(): String = builder.toString()

    private fun String.normalizeSpaces(): String =
        trim().replace(WHITESPACE_RUN, " ")

    private companion object {
        const val SEPARATOR = "\n"
        val WHITESPACE_RUN = Regex("[ \\t\\u00A0]+")
    }
}
