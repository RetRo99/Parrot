package com.retro99.opds.api.model

/** Lossless language-tagged text. Untagged wire strings use `und`. */
data class OpdsText(val translations: Map<String, String>) {
    constructor(text: String, language: String? = null) : this(mapOf((language?.takeIf { it.isNotBlank() } ?: "und") to text))

    /** Pure display policy; parsers retain the data and never invoke this helper. */
    fun select(preferredLanguages: List<String>): String? {
        for (preferred in preferredLanguages) {
            translations.entries.firstOrNull { it.key.equals(preferred, ignoreCase = true) }?.let { return it.value }
            val primary = preferred.substringBefore('-')
            translations.entries.firstOrNull { it.key.equals(primary, ignoreCase = true) }?.let { return it.value }
            translations.entries.firstOrNull { it.key.substringBefore('-').equals(primary, ignoreCase = true) }?.let { return it.value }
        }
        return translations.entries.firstOrNull { it.key.equals("und", ignoreCase = true) }?.value
            ?: translations.values.firstOrNull()
    }

    fun isBlank(): Boolean = translations.values.all { it.isBlank() }
    fun isNotBlank(): Boolean = !isBlank()
}
