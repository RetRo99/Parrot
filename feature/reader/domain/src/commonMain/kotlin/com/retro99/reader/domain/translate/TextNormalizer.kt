package com.retro99.reader.domain.translate

/**
 * Text prepared for matching, with a map from each character back to its index in the
 * original text.
 */
internal class NormalizedText(val text: String, private val originalIndexes: IntArray) {
    fun originalIndex(normalizedIndex: Int): Int = when {
        originalIndexes.isEmpty() -> 0
        normalizedIndex >= originalIndexes.size -> originalIndexes.last() + 1
        else -> originalIndexes[normalizedIndex.coerceAtLeast(0)]
    }
}

/**
 * Normalizes text so two editions of the same words compare equal: accents folded, lowercase,
 * quotes straightened, soft hyphens and zero-width characters removed, whitespace collapsed.
 */
internal object TextNormalizer {

    private val removed = setOf('­', '​', '‌', '‍', '⁠', '﻿')

    private val replacements: Map<Char, String> = buildMap {
        fun map(chars: String, replacement: String) {
            chars.forEach { char -> put(char, replacement) }
        }
        map("àáâãäåāăą", "a")
        map("çćĉċč", "c")
        map("ďđ", "d")
        map("èéêëēĕėęě", "e")
        map("ĝğġģ", "g")
        map("ĥħ", "h")
        map("ìíîïĩīĭįı", "i")
        map("ĵ", "j")
        map("ķ", "k")
        map("ĺļľŀł", "l")
        map("ñńņňŉ", "n")
        map("òóôõöøōŏő", "o")
        map("ŕŗř", "r")
        map("śŝşšș", "s")
        map("ţťŧț", "t")
        map("ùúûüũūŭůűų", "u")
        map("ŵ", "w")
        map("ýÿŷ", "y")
        map("źżž", "z")
        map("æ", "ae")
        map("œ", "oe")
        map("ß", "ss")
        map("‘’‚‛′", "'")
        map("“”„‟″«»", "\"")
        map("–—―−", "-")
        map("…", "...")
    }

    fun normalize(text: String): NormalizedText {
        val builder = StringBuilder(text.length)
        val indexes = IntArray(text.length * 3)
        var count = 0
        fun add(char: Char, index: Int) {
            builder.append(char)
            indexes[count++] = index
        }
        text.forEachIndexed { index, original ->
            if (original in removed) return@forEachIndexed
            if (original.isWhitespace()) {
                if (count > 0 && builder[count - 1] != ' ') add(' ', index)
                return@forEachIndexed
            }
            val lower = original.lowercaseChar()
            val replacement = replacements[lower]
            if (replacement == null) {
                add(lower, index)
            } else {
                replacement.forEach { char -> add(char, index) }
            }
        }
        while (count > 0 && builder[count - 1] == ' ') {
            builder.setLength(count - 1)
            count--
        }
        return NormalizedText(builder.toString(), indexes.copyOf(count))
    }

    fun normalizeString(text: String): String = normalize(text).text
}
