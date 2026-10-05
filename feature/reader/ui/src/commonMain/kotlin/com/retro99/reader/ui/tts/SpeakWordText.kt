package com.retro99.reader.ui.tts

/**
 * Normalises a selected surface form for one-word speech: the form as printed (not the
 * headword), minus the invisibles EPUB text carries and the punctuation of the selection.
 * Internal apostrophes and hyphens stay. Case is lowered unless the form is a genuine
 * all-caps acronym (NATO, HTML).
 *
 * No trailing full stop: the 2026-10-05 single-word clips measured clean decaying tails on
 * every engine (docs/tts-bench-results.md), so there is no clipped tail to mask.
 */
fun prepareWordForSpeech(surfaceForm: String): String {
    val cleaned = surfaceForm
        .filter { char -> char !in INVISIBLE_CHARACTERS && char != SOFT_HYPHEN }
        .replace('’', '\'')
        .trim()
        .trim { char -> !char.isLetterOrDigit() && char != '\'' && char != '-' }
        .trim('\'', '-')
    val isAcronym = cleaned.length in 2..ACRONYM_MAX_LENGTH &&
        cleaned.all { char -> char.isLetter() && char.isUpperCase() }
    return if (isAcronym) cleaned else cleaned.lowercase()
}

/**
 * Whether the form is one speakable word: at least one letter, no internal whitespace, only
 * letters, digits, apostrophes and hyphens inside. Digits-only selections, symbols and
 * phrases (more than one token) are not words and get no speaker button.
 */
fun isSpeakableWordForm(surfaceForm: String): Boolean {
    val prepared = prepareWordForSpeech(surfaceForm)
    return prepared.isNotEmpty() &&
        prepared.length <= MAX_WORD_LENGTH &&
        prepared.any { char -> char.isLetter() } &&
        prepared.all { char -> char.isLetterOrDigit() || char == '\'' || char == '-' }
}

private const val SOFT_HYPHEN = '\u00AD'
private const val ACRONYM_MAX_LENGTH = 5
private const val MAX_WORD_LENGTH = 100

private val INVISIBLE_CHARACTERS = setOf(
    '\u200B', // zero-width space
    '\u200C', // zero-width non-joiner
    '\u200D', // zero-width joiner
    '\u200E', // left-to-right mark
    '\u200F', // right-to-left mark
    '\u2060', // word joiner
    '\u2066', '\u2067', '\u2068', '\u2069', // bidi isolates
    '\uFEFF', // byte order mark
)
