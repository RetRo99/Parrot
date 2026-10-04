package com.retro99.database.api.saved

import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow

data class SavedItemMutation(
    val item: SavedItemEntity,
    val outboxEntry: SyncOutboxEntry,
)

/**
 * Saved items are kept when signing out of a server or Parrot Cloud, so this is
 * deliberately not a DataClearable.
 */
interface SavedItemsDatabase {

    /** Writes the items and queues their sync mutations in one transaction. */
    suspend fun upsertWithMutations(mutations: List<SavedItemMutation>)

    /** Writes items without queueing anything (local-only fields, remote changes). */
    suspend fun upsert(items: List<SavedItemEntity>)

    /**
     * Stores [remote] when [shouldReplace] says it wins over the local row (null when
     * there is none). A replaced row drops its pending, never-sent mutation.
     * Returns true when [remote] was stored.
     */
    suspend fun applyRemote(
        remote: SavedItemEntity,
        shouldReplace: (local: SavedItemEntity?) -> Boolean,
    ): Boolean

    /** Live items of any of these copies, in book order. */
    fun observeForBooks(bookKeys: Set<String>, bookUuids: Set<String>): Flow<List<SavedItemEntity>>

    suspend fun getForBooks(bookKeys: Set<String>, bookUuids: Set<String>): List<SavedItemEntity>

    /** Every live item, most recently changed first. */
    fun observeAll(): Flow<List<SavedItemEntity>>

    suspend fun get(id: String): SavedItemEntity?

    suspend fun getSnippetPending(bookUuid: String): List<SavedItemEntity>

    suspend fun getUnsynced(): List<SavedItemEntity>

    /** Parrot Cloud acknowledged the item at [revision]; nothing else about the row changes. */
    suspend fun setRemoteRevision(id: String, revision: Long)

    /** Saved item mutations still in the outbox. */
    fun observePendingSyncCount(): Flow<Long>

    suspend fun clearAll()
}
