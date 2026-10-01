package com.retro99.server.api

/**
 * The reading order (spine) of a server ebook downloaded to this device: its chapters' hrefs,
 * as the reader's locators name them. Audiobookshelf's EPUB CFIs address chapters by spine
 * position, so its positions are read and written through this (B4).
 */
fun interface EbookReadingOrderSource {
    /** Null when the book isn't on this device or can't be read. */
    suspend fun readingOrderHrefs(bookUuid: String): List<String>?
}
