package com.retro99.server.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A short excerpt around an ebook position: the words before it and the words from it on.
 * Stored on this device only, as JSON in `position.text_anchor`, and never sent to a server.
 */
@Serializable
data class TextAnchor(
    val before: String,
    val after: String,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val BEFORE_WORDS = 20
        const val AFTER_WORDS = 30

        private val json = Json { ignoreUnknownKeys = true }
        private val whitespace = Regex("\\s+")

        /**
         * Keeps the last [BEFORE_WORDS] words of [before] and the first [AFTER_WORDS] words of
         * [after]. Null when there is no text after the position.
         */
        fun of(before: String?, after: String?): TextAnchor? {
            val afterWords = words(after).take(AFTER_WORDS)
            if (afterWords.isEmpty()) return null
            return TextAnchor(
                before = words(before).takeLast(BEFORE_WORDS).joinToString(" "),
                after = afterWords.joinToString(" "),
            )
        }

        fun fromJson(value: String?): TextAnchor? {
            if (value.isNullOrBlank()) return null
            return try {
                json.decodeFromString(serializer(), value)
            } catch (exception: IllegalArgumentException) {
                null
            }
        }

        private fun words(text: String?): List<String> =
            text.orEmpty().trim().split(whitespace).filter { word -> word.isNotEmpty() }
    }
}
