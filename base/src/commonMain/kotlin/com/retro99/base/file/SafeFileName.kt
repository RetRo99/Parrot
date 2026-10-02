package com.retro99.base.file

/**
 * Maps a server- or user-supplied id to a single file-name segment.
 * Separators and dots become '_', so the result can never traverse.
 * Lossy: distinct ids can share a name, so only use it for local ids.
 */
fun String.safeFileName(): String = map { character ->
    if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
}.joinToString("")

/**
 * Injective variant for server ids used as cache keys. Already-safe ids are
 * kept verbatim so existing caches stay valid; anything else is escaped
 * behind a '%' prefix, which a verbatim name can never contain.
 */
fun String.encodeFileNameSegment(): String {
    if (isVerbatimSafe()) return this
    return buildString {
        append(ESCAPE)
        this@encodeFileNameSegment.forEach { character ->
            if (character.isAsciiAlphanumeric() || character == '-' || character == '_') {
                append(character)
            } else {
                append(ESCAPE)
                append(character.code.toString(HEX_RADIX).uppercase().padStart(ESCAPE_WIDTH, '0'))
            }
        }
    }
}

private fun String.isVerbatimSafe(): Boolean =
    isNotEmpty() && this != "." && this != ".." && all { character ->
        character.isAsciiAlphanumeric() || character == '.' || character == '-' || character == '_'
    }

private fun Char.isAsciiAlphanumeric(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private const val ESCAPE = '%'
private const val HEX_RADIX = 16
private const val ESCAPE_WIDTH = 4
