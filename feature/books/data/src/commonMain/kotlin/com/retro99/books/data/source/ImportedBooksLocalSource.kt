package com.retro99.books.data.source

import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow

/**
 * Local data source for imported books.
 */
interface ImportedBooksLocalSource {

    suspend fun saveImportedBook(book: BookDomainModel.LocalBook): CompletableResult

    fun observeAllImportedBooks(): Flow<List<BookDomainModel.LocalBook>>

    suspend fun getImportedBookByUuid(uuid: String): BookDomainModel.LocalBook?

    suspend fun deleteImportedBook(uuid: String): CompletableResult

    suspend fun updateLastOpenedAt(uuid: String): CompletableResult
}

