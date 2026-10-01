package com.retro99.database.api.library

interface LibraryBookMergeDatabase {
    /**
     * Moves everything that belongs to [fromId] onto [intoId] and deletes [fromId],
     * in one transaction. Used when Parrot Cloud reports that [fromId] duplicates [intoId].
     *
     * @return paths of device files that became redundant. Delete them after this returns.
     */
    suspend fun mergeLibraryBook(fromId: String, intoId: String): List<String>
}
