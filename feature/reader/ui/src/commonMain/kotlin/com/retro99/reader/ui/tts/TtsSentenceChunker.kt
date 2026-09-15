package com.retro99.reader.ui.tts

object TtsSentenceChunker {

    private const val MAX_CHUNK_CHARS = 280

    private val WHITESPACE = Regex("\\s+")

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "no", "vol",
        "ch", "pp", "fig", "inc", "ltd", "co", "corp", "dept", "univ", "approx", "est",
        "al", "eg", "ie", "am", "pm", "us", "uk", "op", "cit", "ibid",
    )

    private val TERMINATORS = charArrayOf('.', '!', '?', '\u2026')

    private val CLAUSE_SEPARATORS = charArrayOf(',', ';', ':', '\u2014', '\u2013')

    private val highSurrogateRange = '\uD800'..'\uDBFF'

    private val lowSurrogateRange = '\uDC00'..'\uDFFF'

    fun chunk(text: String): List<String> {
        val normalized = text.replace(WHITESPACE, " ").trim()
        if (normalized.isEmpty()) return emptyList()

        return splitIntoSentences(normalized)
            .flatMap { splitLongChunk(it) }
            .map { chunk -> chunk.trim() }
            .filter { chunk -> chunk.isNotEmpty() }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        var index = 0
        while (index < text.length) {
            val char = text[index]
            current.append(char)

            if (isTerminator(char) && isSentenceBoundary(text, index)) {
                val end = consumeTrailingQuotes(text, index)
                for (extra in (index + 1)..end) {
                    current.append(text[extra])
                }
                sentences.add(current.toString())
                current.clear()
                index = end + 1
                while (index < text.length && text[index] == ' ') {
                    index++
                }
                continue
            }

            index++
        }

        if (current.isNotBlank()) {
            sentences.add(current.toString())
        }

        return sentences
    }

    private fun isSentenceBoundary(text: String, index: Int): Boolean {
        val char = text[index]

        if (char == '.') {
            if (isDecimalPoint(text, index)) return false
            if (isAbbreviation(text, index)) return false
        }

        val nextIndex = index + 1
        if (nextIndex >= text.length) return true

        var lookahead = nextIndex
        while (lookahead < text.length && isOpeningOrClosingQuote(text[lookahead])) {
            lookahead++
        }
        if (lookahead >= text.length) return true

        return text[lookahead] == ' '
    }

    private fun isDecimalPoint(text: String, index: Int): Boolean {
        val previous = text.getOrNull(index - 1) ?: return false
        val next = text.getOrNull(index + 1) ?: return false
        return previous.isDigit() && next.isDigit()
    }

    private fun isAbbreviation(text: String, index: Int): Boolean {
        var start = index - 1
        while (start >= 0 && !text[start].isWhitespace() && !isClauseOrQuote(text[start])) {
            start--
        }
        val token = text.substring(start + 1, index)
        if (token.isEmpty()) return false

        val lower = token.lowercase()
        if (lower in ABBREVIATIONS) return true
        val isInitialism = token.length <= 2 && token.all { character ->
            character.isUpperCase() || !character.isLetter()
        }
        return isInitialism
    }

    private fun splitLongChunk(chunk: String): List<String> {
        if (chunk.length <= MAX_CHUNK_CHARS) return listOf(chunk)

        val splitAt = findSplitPosition(chunk).avoidSplittingSurrogatePair(chunk)

        val head = chunk.substring(0, splitAt).trim()
        val tail = chunk.substring(splitAt).trim()
        if (head.isEmpty()) return listOf(chunk)
        if (tail.isEmpty()) return listOf(head)

        return listOf(head) + splitLongChunk(tail)
    }

    private fun findSplitPosition(chunk: String): Int {
        val clauseBoundary = findClauseBoundary(chunk)
        if (clauseBoundary >= 0) return clauseBoundary + 1

        val whitespaceBoundary = chunk.lastIndexOf(' ', startIndex = MAX_CHUNK_CHARS)
        return whitespaceBoundary.takeIf { index -> index > 0 } ?: MAX_CHUNK_CHARS
    }

    private fun Int.avoidSplittingSurrogatePair(text: String): Int {
        if (this !in 1 until text.length) return this

        val previous = text[this - 1]
        val current = text[this]
        return if (previous in highSurrogateRange && current in lowSurrogateRange) {
            this - 1
        } else {
            this
        }
    }

    private fun findClauseBoundary(chunk: String): Int {
        var best = -1
        var index = MAX_CHUNK_CHARS.coerceAtMost(chunk.length - 1)
        while (index >= MAX_CHUNK_CHARS / 2) {
            if (isClauseSeparator(chunk[index]) && chunk.getOrNull(index + 1) == ' ') {
                best = index
                break
            }
            index--
        }
        return best
    }

    private fun consumeTrailingQuotes(text: String, index: Int): Int {
        var cursor = index
        while (cursor + 1 < text.length && isClosingQuote(text[cursor + 1])) {
            cursor++
        }
        return cursor
    }

    private fun isTerminator(char: Char): Boolean = char in TERMINATORS

    private fun isClauseSeparator(char: Char): Boolean = char in CLAUSE_SEPARATORS

    private fun isOpeningOrClosingQuote(char: Char): Boolean =
        char == '"' ||
                char == '\u201c' ||
                char == '\u201d' ||
                char == '\'' ||
                char == '\u2018' ||
                char == '\u2019'

    private fun isClosingQuote(char: Char): Boolean =
        char == '"' || char == '\u201d' || char == '\'' || char == '\u2019'

    private fun isClauseOrQuote(char: Char): Boolean =
        isClauseSeparator(char) || isOpeningOrClosingQuote(char) || isTerminator(char)

}
