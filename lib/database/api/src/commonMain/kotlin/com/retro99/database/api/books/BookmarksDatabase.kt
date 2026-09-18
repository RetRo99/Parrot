package com.retro99.database.api.books

import com.retro99.database.api.DataClearable
import kotlinx.coroutines.flow.Flow

interface BookmarksDatabase : DataClearable {

    suspend fun addBookmark(bookmark: BookmarkEntity)

    suspend fun upsertBookmarksWithMutations(mutations: List<BookmarkMutation>)

    fun observeBookmarks(bookUuid: String): Flow<List<BookmarkEntity>>

    suspend fun getBookmarks(bookUuid: String): List<BookmarkEntity>

    suspend fun getBookmark(id: String): BookmarkEntity?

    suspend fun deleteBookmarksForBook(bookUuid: String)

    override suspend fun clearAllData()
}
