package com.retro99.server.api

/**
 * The full spine of a server ebook downloaded to this device: every `<itemref>` in order,
 * `linear="no"` ones included, as the reader's locators name them. Audiobookshelf's EPUB CFIs
 * address chapters by spine position (`/6/N` counts every itemref), so its positions are read
 * and written through this (B4). Not the linear reading order used for text.
 */
fun interface EbookReadingOrderSource {
    /** Null when the book isn't on this device or can't be read. */
    suspend fun readingOrderHrefs(bookUuid: String): List<String>?
}
