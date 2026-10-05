package com.retro99.reader.ui.reader

import com.retro99.books.domain.model.progressPercentOf

/**
 * The single book-progress percentage shown across the reader (strip, Contents, bookmarks).
 * Delegates to the shared [progressPercentOf], the same value the library and book details
 * show for the same book.
 */
internal fun bookPercent(totalProgression: Double?): Int = progressPercentOf(totalProgression)
