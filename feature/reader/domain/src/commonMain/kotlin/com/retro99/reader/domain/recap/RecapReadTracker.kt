package com.retro99.reader.domain.recap

/**
 * One visible run of chapter text. [start] and [end] are UTF-16 offsets
 * into the chapter's text, so the same words keep the same range however
 * the page is laid out. [startsBlock] marks a new paragraph or heading.
 */
data class RecapTextPiece(
    val start: Int,
    val end: Int,
    val text: String,
    val startsBlock: Boolean,
) {
    override fun toString(): String =
        "RecapTextPiece(start=$start, end=$end, chars=${text.length}, startsBlock=$startsBlock)"
}

/**
 * Remembers what a session already captured, so rereading or going back
 * never appends the same text twice. Pages are deduped by chapter range,
 * spoken sentences by chapter and sentence index. Not thread-safe.
 */
class RecapReadTracker {
    private val pageRanges = mutableMapOf<String, MutableList<Span>>()
    private val heardSentences = mutableSetOf<String>()

    /**
     * The part of a settled page not captured before, or null when there is
     * none. Marks the page's ranges as captured.
     */
    fun takeUnreadPageText(chapterKey: String, pieces: List<RecapTextPiece>): String? {
        val covered = pageRanges.getOrPut(chapterKey) { mutableListOf() }
        val out = StringBuilder()
        var lastEnd: Int? = null
        for (piece in pieces.sortedBy { it.start }) {
            if (piece.end <= piece.start) continue
            // Offsets that don't match the text can't be clipped safely.
            val exact = piece.text.length == piece.end - piece.start
            val gaps = uncovered(covered, piece.start, piece.end)
            val parts = when {
                exact -> gaps
                gaps.singleOrNull() == Span(piece.start, piece.end) -> gaps
                else -> emptyList()
            }
            for (gap in parts) {
                val raw = if (exact) {
                    piece.text.substring(gap.start - piece.start, gap.end - piece.start)
                } else {
                    piece.text
                }
                val text = raw.collapseWhitespace()
                if (text.isBlank()) continue
                if (out.isNotEmpty()) {
                    out.append(
                        when {
                            piece.startsBlock && gap.start == piece.start -> "\n"
                            lastEnd == gap.start -> ""
                            else -> " "
                        },
                    )
                }
                out.append(text)
                lastEnd = gap.end
            }
            cover(covered, piece.start, piece.end)
        }
        return out.toString().tidyLines().takeIf { it.isNotEmpty() }
    }

    /** The sentence's text the first time it is heard, else null. */
    fun takeUnheardSentence(chapterKey: String, sentenceIndex: Int, text: String,
        startOffset: Int? = null, rawText: String? = null): String? {
        val clean = text.collapseWhitespace().trim()
        if (clean.isEmpty()) return null
        if (!heardSentences.add("$chapterKey#$sentenceIndex")) return null
        if (startOffset != null && startOffset >= 0 && !rawText.isNullOrBlank()) {
            return takeUnreadPageText(chapterKey, listOf(RecapTextPiece(startOffset,
                startOffset + rawText.length, rawText, false)))
        }
        return clean
    }

    private data class Span(val start: Int, val end: Int)

    private fun uncovered(covered: List<Span>, start: Int, end: Int): List<Span> {
        val gaps = mutableListOf<Span>()
        var cursor = start
        for (span in covered) {
            if (span.end <= cursor) continue
            if (span.start >= end) break
            if (span.start > cursor) gaps += Span(cursor, span.start)
            cursor = maxOf(cursor, span.end)
            if (cursor >= end) break
        }
        if (cursor < end) gaps += Span(cursor, end)
        return gaps
    }

    /** Adds [start, end) and keeps the list sorted and merged. */
    private fun cover(covered: MutableList<Span>, start: Int, end: Int) {
        var from = start
        var to = end
        val kept = covered.filter { span ->
            val overlaps = span.start <= to && span.end >= from
            if (overlaps) {
                from = minOf(from, span.start)
                to = maxOf(to, span.end)
            }
            !overlaps
        }
        covered.clear()
        covered += kept
        covered += Span(from, to)
        covered.sortBy { it.start }
    }

    private fun String.collapseWhitespace(): String = replace(WHITESPACE, " ")

    private fun String.tidyLines(): String =
        lines().map { it.trim().replace(SPACES, " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val SPACES = Regex(" {2,}")
    }
}

/** Text helpers for what the recap API receives. */
object RecapText {
    private val SENTENCE_ENDS = setOf('.', '!', '?', '…')
    private val CLOSERS = setOf('"', '\'', '”', '’', '»', ')')

    /**
     * The last sentence of [text], which may be a fragment when reading
     * stopped mid-sentence. Longer ones keep their last [maxChars] chars,
     * starting at a word.
     */
    fun lastSentence(
        text: String?,
        maxChars: Int = RecapLimits.MAX_LAST_SENTENCE_CHARS,
    ): String? {
        val line = text?.trim()?.substringAfterLast('\n')?.trim()
        if (line.isNullOrEmpty()) return null
        var start = 0
        var i = 0
        while (i < line.length) {
            if (line[i] !in SENTENCE_ENDS) {
                i++
                continue
            }
            var j = i + 1
            while (j < line.length && line[j] in CLOSERS) j++
            if (j < line.length && line[j].isWhitespace()) {
                var next = j
                while (next < line.length && line[next].isWhitespace()) next++
                if (next < line.length) start = next
                i = next
            } else {
                i = j
            }
        }
        val sentence = line.substring(start).trim()
        if (sentence.length <= maxChars) return sentence
        val tail = sentence.takeLast(maxChars)
        val word = tail.indexOfFirst { it.isWhitespace() }
        return if (word in 0 until tail.length - 1) tail.substring(word + 1).trim() else tail
    }
}

/** The languages the recap API writes in. */
object RecapLanguages {
    val SUPPORTED: Set<String> = setOf("en", "sl", "de", "fr", "es", "it", "hr")

    /** "sl-SI" → "sl"; null when unsupported or blank. */
    fun normalize(tag: String?): String? {
        val base = tag?.trim()?.split('-', '_')?.firstOrNull()?.lowercase()
        return base?.takeIf { it in SUPPORTED }
    }

    /** The book's language when supported, else the app's, else null. */
    fun resolve(bookLanguage: String?, appLanguage: String?): String? =
        normalize(bookLanguage) ?: normalize(appLanguage)
}
