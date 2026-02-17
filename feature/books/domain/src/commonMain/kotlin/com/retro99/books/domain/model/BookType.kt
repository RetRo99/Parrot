package com.retro99.books.domain.model

/**
 * Represents the type of book being read.
 * Used to determine which reader features and controls to display.
 *
 * @property value The query format string used in API requests.
 */
enum class BookType(val value: String) {
    /**
     * Standard ebook without audio narration.
     */
    EBOOK("ebook"),

    /**
     * Audiobook with audio-only playback.
     */
    AUDIOBOOK("audiobook"),

    /**
     * ReadAloud book - EPUB with embedded media overlays
     * that synchronize professional audio narration with text.
     */
    READALOUD("readaloud"),

    /**
     * Locally imported EPUB file.
     * Similar to EBOOK but without server sync, statistics, or audio features.
     */
    IMPORTED("imported"),
    ;

    /**
     * Whether this book type is a locally imported file (not from server).
     */
    val isLocal: Boolean get() = this == IMPORTED

    companion object {
        fun fromValue(value: String): BookType =
            entries.find { it.value == value.lowercase() } ?: EBOOK
    }
}

