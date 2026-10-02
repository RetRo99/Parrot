package com.retro99.reader.domain.recap

/**
 * Read-text buffer. Appends in reading order and never drops text: a
 * session sends everything it read. Not thread-safe; callers serialise.
 */
class RecapExcerptBuffer(initial: String? = null) {
    private val builder = StringBuilder(initial.orEmpty())
    private var lastSegment: String? = null

    val length: Int get() = builder.length

    fun isEmpty(): Boolean = builder.isEmpty()

    /** Returns false when [text] was blank or repeated the previous segment. */
    fun append(text: String): Boolean {
        val segment = text.normalizeSpaces()
        if (segment.isEmpty() || segment == lastSegment) return false
        lastSegment = segment
        if (builder.isNotEmpty()) builder.append(SEPARATOR)
        builder.append(segment)
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
