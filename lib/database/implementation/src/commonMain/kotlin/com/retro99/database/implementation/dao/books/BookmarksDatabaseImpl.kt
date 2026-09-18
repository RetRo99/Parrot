package com.retro99.database.implementation.dao.books

import com.retro99.database.api.books.BookmarkEntity
import com.retro99.database.api.books.BookmarkMutation
import com.retro99.database.api.books.BookmarksDatabase
import kotlinx.coroutines.flow.Flow

internal class BookmarksDatabaseImpl(
    private val sqlDelightDao: BookmarksSqlDelightDao,
) : BookmarksDatabase {

    override suspend fun addBookmark(bookmark: BookmarkEntity) {
        sqlDelightDao.insertBookmark(bookmark)
    }

    override suspend fun upsertBookmarksWithMutations(mutations: List<BookmarkMutation>) {
        sqlDelightDao.upsertBookmarksWithMutations(mutations)
    }

    override fun observeBookmarks(bookUuid: String): Flow<List<BookmarkEntity>> {
        return sqlDelightDao.observeBookmarks(bookUuid)
    }

    override suspend fun getBookmarks(bookUuid: String): List<BookmarkEntity> {
        return sqlDelightDao.getBookmarks(bookUuid)
    }

    override suspend fun getBookmark(id: String): BookmarkEntity? {
        return sqlDelightDao.getBookmark(id)
    }

    override suspend fun deleteBookmarksForBook(bookUuid: String) {
        sqlDelightDao.deleteBookmarksForBook(bookUuid)
    }

    override suspend fun clearAllData() {
        sqlDelightDao.deleteAllBookmarks()
    }
}
