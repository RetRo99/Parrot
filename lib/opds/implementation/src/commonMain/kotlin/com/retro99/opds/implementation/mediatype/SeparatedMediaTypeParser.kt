package com.retro99.opds.implementation.mediatype

import com.retro99.opds.api.model.OpdsMediaType

/**
 * RFC 9110-style media type parsing without exact-string comparison (plan
 * §4): lower-cased type and subtype, parameter keys lower-cased, values
 * unquoted and case-preserving, parameter order irrelevant, commas between
 * accepted preference list entries handled by taking the first entry.
 */
class SeparatedMediaTypeParser {

    fun parse(header: String?): OpdsMediaType? {
        if (header.isNullOrBlank()) return null
        // A header may carry several accepted types; parse the first entry.
        val firstEntryEnd = header.indexOfIgnoringQuoted(',')
        if (firstEntryEnd == -1) {
            return parseEntry(header)
        }
        return parseEntry(header.substring(0, firstEntryEnd))
    }

    private fun parseEntry(entry: String?): OpdsMediaType? {
        val value = entry?.trim() ?: return null
        val splitPoint = value.indexOf('/')
        if (splitPoint <= 0) return null

        val mainType = value.substring(0, splitPoint).trim().lowercase().takeIf { it.isValidToken() } ?: return null
        val rest = value.substring(splitPoint + 1)
        val separatorIndex = rest.indexOf(';')
        val subType: String
        val parameterBlob: String
        if (separatorIndex == -1) {
            subType = rest.trim().lowercase()
            parameterBlob = ""
        } else {
            subType = rest.substring(0, separatorIndex).trim().lowercase()
            parameterBlob = rest.substring(separatorIndex + 1)
        }
        if (!subType.isValidToken()) return null

        val parameters = parseParameters(parameterBlob)
        return OpdsMediaType(mainType, subType, parameters)
    }

    private fun parseParameters(blob: String): Map<String, String> {
        if (blob.isBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        var index = 0
        while (index < blob.length) {
            when (blob[index]) {
                ';', ',' -> { index++; continue }
                else -> {}
            }
            val equalsIndex = blob.indexOf('=', index)
            if (equalsIndex == -1) break
            val key = blob.substring(index, equalsIndex).trim().lowercase()
            var valueStart = equalsIndex + 1
            while (valueStart < blob.length && blob[valueStart] == ' ') valueStart++
            val value: String
            when {
                valueStart < blob.length && blob[valueStart] == '"' -> {
                    val close = blob.indexOf('"', valueStart + 1)
                    if (close == -1) break
                    value = blob.substring(valueStart + 1, close)
                    index = close + 1
                }
                else -> {
                    val end = blob.indexOf(';', valueStart).takeIf { it >= 0 } ?: blob.length
                    value = blob.substring(valueStart, end).trim()
                    index = end
                }
            }
            if (key.isNotEmpty() && key !in out) out[key] = value
        }
        return out
    }

    /** Index of the first comma outside a quoted parameter value. */
    private fun String.indexOfIgnoringQuoted(needle: Char): Int {
        var inQuote = false
        forEachIndexed { index, c ->
            when {
                c == '"' -> inQuote = !inQuote
                c == needle && !inQuote -> return index
                else -> {}
            }
        }
        return -1
    }
}

private fun String.isValidToken(): Boolean =
    isNotEmpty() && all { it.isAsciiLetter() || it.isAsciiDigit() || it in "+-._" }

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
