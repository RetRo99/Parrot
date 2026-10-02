package com.retro99.base.file

/**
 * Maps a server- or user-supplied id to a single file-name segment.
 * Separators and dots become '_', so the result can never traverse.
 */
fun String.safeFileName(): String = map { character ->
    if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
}.joinToString("")
