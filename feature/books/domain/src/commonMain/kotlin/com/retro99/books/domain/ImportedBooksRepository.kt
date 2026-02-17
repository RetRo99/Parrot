package com.retro99.books.domain

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing locally imported books.
 */
interface ImportedBooksRepository {

    /**
     * Saves an imported book to the database.
     */
    suspend fun saveImportedBook(book: BookDomainModel.LocalBook): CompletableResult

    /**
     * Observes all imported books.
     */
    fun observeAllImportedBooks(): Flow<List<BookDomainModel.LocalBook>>

    /**
     * Gets an imported book by its UUID.
     */
    suspend fun getImportedBookByUuid(uuid: String): AppResult<BookDomainModel.LocalBook>

    /**
     * Deletes an imported book by its UUID.
     * Also deletes the associated file and cover.
     */
    suspend fun deleteImportedBook(uuid: String): CompletableResult

    /**
     * Updates the last opened timestamp for an imported book.
     */
    suspend fun updateLastOpenedAt(uuid: String): CompletableResult
}

