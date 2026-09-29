package com.retro99.reader.ui.reader

/**
 * The single book-progress percentage shown across the reader (strip, Contents, bookmarks).
 * Floors, so "71%" stays until the reader has truly reached 72%, and 100% only appears at the end.
 */
internal fun bookPercent(totalProgression: Double?): Int =
    ((totalProgression ?: 0.0).coerceIn(0.0, 1.0) * 100).toInt()
