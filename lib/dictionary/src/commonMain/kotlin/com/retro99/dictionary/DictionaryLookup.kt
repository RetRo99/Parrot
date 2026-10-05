package com.retro99.dictionary

import kotlinx.serialization.Serializable

@Serializable
data class DictionarySense(val gloss: String, val example: String? = null)
@Serializable
data class DictionaryGroup(val partOfSpeech: String, val senses: List<DictionarySense>)
@Serializable
data class DictionaryEntry(val headword: String, val ipa: String? = null, val groups: List<DictionaryGroup>) {
    val firstSense: DictionarySense get() = groups.first().senses.first()
    val partOfSpeech: String get() = groups.first().partOfSpeech
    val copyText: String get() = "$headword — ${firstSense.gloss}"
}

sealed interface DefinitionState {
    data class Found(val entry: DictionaryEntry) : DefinitionState
    data class NotFound(val word: String) : DefinitionState
    data class Pack(val state: DictionaryPackState) : DefinitionState
}

/** Punctuation at the edges is ignored; phrases, numbers and symbols aren't dictionary words. */
fun dictionaryWord(selection: String): String? {
    val word = selection.replace("\u00ad", "").replace('‑', '-').replace('‐', '-').trim()
        .trim { !it.isLetterOrDigit() && !it.isDictionaryMark() && it != '\'' && it != '’' }
        .trim('\'', '’').replace('’', '\'')
    return word.takeIf { it.length in 1..100 && it.any(Char::isLetter) &&
        it.all { char -> char.isLetterOrDigit() || char.isDictionaryMark() || char == '\'' || char == '-' } }
}

private fun Char.isDictionaryMark(): Boolean = category in setOf(
    CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK,
)

fun isEnglish(language: String?): Boolean = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') in setOf("en", "eng", "english")

/** Exact → supplied forms → conservative rules → hyphen parts. All reads stay on the device. */
fun lookupDictionary(word: String, exact: (String) -> DictionaryEntry?, forms: (String) -> List<String>): DictionaryEntry? {
    val key = word.lowercase()
    exact(key)?.let { return it }
    forms(key).forEach { form -> exact(form)?.let { return it } }
    val candidates = buildList {
        if (key.endsWith("'s")) add(key.dropLast(2))
        if (key.endsWith("n't")) {
            add(when (key) { "can't" -> "can"; "won't" -> "will"; "shan't" -> "shall"; else -> key.dropLast(3) })
        }
        for (suffix in listOf("ing", "ed")) if (key.endsWith(suffix) && key.length > suffix.length + 2) {
            val stem = key.dropLast(suffix.length)
            add(stem); add(stem + "e")
            if (stem.length > 2 && stem.last() == stem[stem.lastIndex - 1]) add(stem.dropLast(1))
            if (suffix == "ed" && stem.endsWith("i")) add(stem.dropLast(1) + "y")
        }
        if (key.endsWith("ies")) add(key.dropLast(3) + "y")
        if (key.endsWith("es")) add(key.dropLast(2))
        if (key.endsWith("s") && !key.endsWith("ss")) add(key.dropLast(1))
    }
    candidates.distinct().forEach { exact(it)?.let { entry -> return entry } }
    if ('-' in key) key.split('-').filter { it.isNotEmpty() }.forEach { part ->
        lookupDictionary(part, exact, forms)?.let { return it }
    }
    return null
}
