package com.retro99.database.implementation.dao.saved

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.retro99.database.api.saved.SavedItemEntity
import com.retro99.database.api.saved.SavedItemMutation
import com.retro99.database.api.saved.SavedItemsDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Saved_items
import com.retro99.database.implementation.SavedItemQueries
import com.retro99.database.implementation.dao.sync.enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class SavedItemsSqlDelightDao(
    private val databaseManager: DatabaseManager,
) : SavedItemsDatabase {
    private val database get() = databaseManager.getDatabase()
    private val savedItemQueries get() = database.savedItemQueries
    private val syncOutboxQueries get() = database.syncOutboxQueries

    override suspend fun upsertWithMutations(mutations: List<SavedItemMutation>) {
        withContext(Dispatchers.IO) {
            database.transaction {
                mutations.forEach { mutation ->
                    savedItemQueries.insertRow(mutation.item)
                    syncOutboxQueries.enqueue(mutation.outboxEntry)
                }
            }
        }
    }

    override suspend fun upsert(items: List<SavedItemEntity>) {
        withContext(Dispatchers.IO) {
            database.transaction {
                items.forEach { item -> savedItemQueries.insertRow(item) }
            }
        }
    }

    override suspend fun applyRemote(
        remote: SavedItemEntity,
        shouldReplace: (local: SavedItemEntity?) -> Boolean,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val local = savedItemQueries.getSavedItemById(remote.id)
                    .executeAsOneOrNull()
                    ?.toEntity()
                if (!shouldReplace(local)) return@transactionWithResult false
                savedItemQueries.insertRow(remote)
                syncOutboxQueries.deletePendingMutationsForEntityAnyUser(
                    entity_type = SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM,
                    entity_id = remote.id,
                )
                true
            }
        }
    }

    override fun observeForBooks(
        bookKeys: Set<String>,
        bookUuids: Set<String>,
    ): Flow<List<SavedItemEntity>> {
        return savedItemQueries.getLiveSavedItemsForBooks(bookKeys, bookUuids)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { row -> row.toEntity() } }
    }

    override suspend fun getForBooks(
        bookKeys: Set<String>,
        bookUuids: Set<String>,
    ): List<SavedItemEntity> {
        return withContext(Dispatchers.IO) {
            savedItemQueries.getLiveSavedItemsForBooks(bookKeys, bookUuids)
                .executeAsList()
                .map { row -> row.toEntity() }
        }
    }

    override fun observeAll(): Flow<List<SavedItemEntity>> {
        return savedItemQueries.getAllLiveSavedItems()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { row -> row.toEntity() } }
    }

    override suspend fun get(id: String): SavedItemEntity? {
        return withContext(Dispatchers.IO) {
            savedItemQueries.getSavedItemById(id).executeAsOneOrNull()?.toEntity()
        }
    }

    override suspend fun getSnippetPending(bookUuid: String): List<SavedItemEntity> {
        return withContext(Dispatchers.IO) {
            savedItemQueries.getSnippetPendingItems(bookUuid)
                .executeAsList()
                .map { row -> row.toEntity() }
        }
    }

    override suspend fun getUnsynced(): List<SavedItemEntity> {
        return withContext(Dispatchers.IO) {
            savedItemQueries.getUnsyncedSavedItems()
                .executeAsList()
                .map { row -> row.toEntity() }
        }
    }

    override suspend fun setRemoteRevision(id: String, revision: Long) {
        withContext(Dispatchers.IO) {
            savedItemQueries.setSavedItemRemoteRevision(revision = revision, id = id)
        }
    }

    override fun observePendingSyncCount(): Flow<Long> {
        return savedItemQueries.countPendingSavedItemMutations()
            .asFlow()
            .mapToOne(Dispatchers.IO)
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            savedItemQueries.deleteAllSavedItems()
        }
    }
}

internal fun SavedItemQueries.insertRow(item: SavedItemEntity) {
    upsertSavedItem(
        id = item.id,
        book_key = item.bookKey,
        book_uuid = item.bookUuid,
        book_title = item.bookTitle,
        book_author = item.bookAuthor,
        type = item.type,
        href = item.href,
        media_type = item.mediaType,
        progression = item.progression,
        total_progression = item.totalProgression,
        position = item.position?.toLong(),
        chapter_title = item.chapterTitle,
        text_before = item.textBefore,
        text_quote = item.textQuote,
        text_after = item.textAfter,
        color = item.color,
        note = item.note,
        audio_href = item.audioHref,
        audio_ms = item.audioMs,
        snippet_pending = if (item.snippetPending) 1L else 0L,
        created_at = item.createdAt,
        updated_at = item.updatedAt,
        deleted_at = item.deletedAt,
        remote_revision = item.remoteRevision,
        word_selected = item.wordSelected,
        word_headword = item.wordHeadword,
        word_language = item.wordLanguage,
        word_gloss = item.wordGloss,
        word_part_of_speech = item.wordPartOfSpeech,
    )
}

private fun Saved_items.toEntity(): SavedItemEntity = SavedItemRow(
    id = id,
    bookKey = book_key,
    bookUuid = book_uuid,
    bookTitle = book_title,
    bookAuthor = book_author,
    type = type,
    href = href,
    mediaType = media_type,
    progression = progression,
    totalProgression = total_progression,
    position = position?.toInt(),
    chapterTitle = chapter_title,
    textBefore = text_before,
    textQuote = text_quote,
    textAfter = text_after,
    color = color,
    note = note,
    audioHref = audio_href,
    audioMs = audio_ms,
    snippetPending = snippet_pending != 0L,
    createdAt = created_at,
    updatedAt = updated_at,
    deletedAt = deleted_at,
    remoteRevision = remote_revision,
    wordSelected = word_selected,
    wordHeadword = word_headword,
    wordLanguage = word_language,
    wordGloss = word_gloss,
    wordPartOfSpeech = word_part_of_speech,
)

private data class SavedItemRow(
    override val id: String,
    override val bookKey: String,
    override val bookUuid: String,
    override val bookTitle: String?,
    override val bookAuthor: String?,
    override val type: String,
    override val href: String,
    override val mediaType: String?,
    override val progression: Double?,
    override val totalProgression: Double?,
    override val position: Int?,
    override val chapterTitle: String?,
    override val textBefore: String?,
    override val textQuote: String?,
    override val textAfter: String?,
    override val color: String?,
    override val note: String?,
    override val audioHref: String?,
    override val audioMs: Long?,
    override val snippetPending: Boolean,
    override val createdAt: String,
    override val updatedAt: String,
    override val deletedAt: String?,
    override val remoteRevision: Long?,
    override val wordSelected: String?,
    override val wordHeadword: String?,
    override val wordLanguage: String?,
    override val wordGloss: String?,
    override val wordPartOfSpeech: String?,
) : SavedItemEntity
