package com.retro99.saved.domain

import com.retro99.saved.domain.model.SavedItem
import kotlinx.coroutines.flow.Flow

interface SavedItemsRepository {

    /** Live items of any of these copies of one book (linked copies included), in book order. */
    fun observeForBook(bookKeys: Set<String>, bookUuids: Set<String>): Flow<List<SavedItem>>

    /** Every live item across books, most recently changed first. */
    fun observeAll(): Flow<List<SavedItem>>

    suspend fun get(id: String): SavedItem?

    /** Saves [items] as they are (callers stamp updatedAt) and queues them for Parrot Cloud. */
    suspend fun save(items: List<SavedItem>)

    /** Tombstones the item and queues the delete. Returns the item as it was, for Undo. */
    suspend fun delete(id: String): SavedItem?

    /** Migrated bookmarks of this book that still need their first sentence. */
    suspend fun getSnippetPending(bookUuid: String): List<SavedItem>

    /** Number of saved item changes not yet acknowledged by Parrot Cloud. */
    fun observePendingSyncCount(): Flow<Long>
}
