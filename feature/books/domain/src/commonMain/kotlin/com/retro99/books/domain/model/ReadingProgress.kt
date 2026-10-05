package com.retro99.books.domain.model

/**
 * The one place a reading fraction becomes a percentage.
 *
 * Every surface that shows how far a book has been read (library rows, book details, the
 * continue-reading card, the reader) uses this, so no two of them can round the same fraction
 * differently. Floors, so "71%" stays until the book has truly reached 72%, and clamps, so a
 * fraction outside 0.0..1.0 never shows as a negative or over 100%.
 */
fun progressPercentOf(totalProgression: Double?): Int =
    ((totalProgression ?: 0.0).coerceIn(0.0, 1.0) * 100).toInt()
