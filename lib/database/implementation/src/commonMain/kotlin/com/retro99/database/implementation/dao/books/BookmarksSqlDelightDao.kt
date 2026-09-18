package com.retro99.database.implementation.dao.books

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.books.BookmarkEntity
import com.retro99.database.api.books.BookmarkMutation
import com.retro99.database.implementation.Bookmarks
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.dao.sync.enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class BookmarksSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val database get() = databaseManager.getDatabase()
    private val bookmarkQueries get() = databaseManager.getDatabase().bookmarkQueries
    private val syncOutboxQueries get() = database.syncOutboxQueries

    suspend fun insertBookmark(bookmark: BookmarkEntity) {
        withContext(Dispatchers.IO) {
            insertBookmarkRow(bookmark)
        }
    }

    suspend fun upsertBookmarksWithMutations(mutations: List<BookmarkMutation>) {
        withContext(Dispatchers.IO) {
            database.transaction {
                mutations.forEach { mutation ->
                    insertBookmarkRow(mutation.bookmark)
                    syncOutboxQueries.enqueue(mutation.outboxEntry)
                }
            }
        }
    }

    fun observeBookmarks(bookUuid: String): Flow<List<BookmarkEntity>> {
        return bookmarkQueries.getBookmarksByBookUuid(bookUuid)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toBookmarkEntity() } }
    }

    suspend fun getBookmarks(bookUuid: String): List<BookmarkEntity> {
        return withContext(Dispatchers.IO) {
            bookmarkQueries.getBookmarksByBookUuid(bookUuid)
                .executeAsList()
                .map { it.toBookmarkEntity() }
        }
    }

    suspend fun getBookmark(id: String): BookmarkEntity? {
        return withContext(Dispatchers.IO) {
            bookmarkQueries.getBookmarkById(id).executeAsOneOrNull()?.toBookmarkEntity()
        }
    }

    suspend fun deleteBookmarksForBook(bookUuid: String) {
        withContext(Dispatchers.IO) {
            bookmarkQueries.deleteBookmarksByBookUuid(bookUuid)
        }
    }

    suspend fun deleteAllBookmarks() {
        withContext(Dispatchers.IO) {
            bookmarkQueries.deleteAllBookmarks()
        }
    }

    private fun Bookmarks.toBookmarkEntity(): BookmarkEntity {
        return BookmarkEntityImpl(
            id = id,
            bookUuid = book_uuid,
            locatorHref = locator_href,
            locatorType = locator_type,
            locatorTitle = locator_title,
            progression = progression,
            totalProgression = total_progression,
            chapterIndex = chapter_index?.toInt(),
            position = position?.toInt(),
            createdAt = created_at,
            sortOrder = sort_order.toInt(),
            remoteRevision = remote_revision,
            deletedAt = deleted_at,
        )
    }

    private fun insertBookmarkRow(bookmark: BookmarkEntity) {
        bookmarkQueries.insertBookmark(
            id = bookmark.id,
            book_uuid = bookmark.bookUuid,
            locator_href = bookmark.locatorHref,
            locator_type = bookmark.locatorType,
            locator_title = bookmark.locatorTitle,
            progression = bookmark.progression,
            total_progression = bookmark.totalProgression,
            chapter_index = bookmark.chapterIndex?.toLong(),
            position = bookmark.position?.toLong(),
            created_at = bookmark.createdAt,
            sort_order = bookmark.sortOrder.toLong(),
            remote_revision = bookmark.remoteRevision,
            deleted_at = bookmark.deletedAt,
        )
    }
}

private data class BookmarkEntityImpl(
    override val id: String,
    override val bookUuid: String,
    override val locatorHref: String,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val progression: Double?,
    override val totalProgression: Double?,
    override val chapterIndex: Int?,
    override val position: Int?,
    override val createdAt: String,
    override val sortOrder: Int,
    override val remoteRevision: Long?,
    override val deletedAt: String?,
) : BookmarkEntity
