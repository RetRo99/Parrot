package com.retro99.books.domain.model.links

import kotlinx.serialization.Serializable

/** Where a copy of a book lives. Your library (device and Parrot Cloud) is one source. */
@Serializable
enum class CopySource(val prefix: String) {
    Library("library"),
    Storyteller("storyteller"),
    Audiobookshelf("audiobookshelf"),
}

/**
 * Portable name for one copy of a book: `library:<bookId>`, `storyteller:<uuid>` or
 * `audiobookshelf:<itemId>`. It doesn't use server config ids, which differ per device.
 */
@Serializable
data class CopyKey(val source: CopySource, val id: String) {
    val value: String get() = "${source.prefix}:$id"

    companion object {
        fun parse(value: String): CopyKey? {
            val prefix = value.substringBefore(':', missingDelimiterValue = "")
            val id = value.substringAfter(':', missingDelimiterValue = "")
            val source = CopySource.entries.firstOrNull { entry -> entry.prefix == prefix }
            return if (source == null || id.isEmpty()) null else CopyKey(source, id)
        }
    }
}
