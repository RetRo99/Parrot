package com.retro99.sync.domain

/**
 * Folds a library book into another one. Parrot Cloud calls for this when it reports
 * that a book pushed from this device duplicates a book it already has.
 */
interface LibraryBookMerger {
    /** Moves everything that belongs to [fromId] onto [intoId] and removes [fromId]. */
    suspend fun merge(fromId: String, intoId: String)
}
