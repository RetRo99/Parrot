package com.retro99.books.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.source.ImportedBooksLocalSource
import com.retro99.books.domain.ImportedBooksRepository
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ImportedBooksRepository::class])
internal class ImportedBooksDataRepository(
    @Provided private val localSource: ImportedBooksLocalSource,
) : ImportedBooksRepository {

    override suspend fun saveImportedBook(book: BookDomainModel.LocalBook): CompletableResult {
        return localSource.saveImportedBook(book)
    }

    override fun observeAllImportedBooks(): Flow<List<BookDomainModel.LocalBook>> {
        return localSource.observeAllImportedBooks()
    }

    override suspend fun getImportedBookByUuid(uuid: String): AppResult<BookDomainModel.LocalBook> {
        val book = localSource.getImportedBookByUuid(uuid)
        return if (book != null) {
            Ok(book)
        } else {
            Err(AppError.UnknownError(Throwable("Imported book not found: $uuid")))
        }
    }

    override suspend fun deleteImportedBook(uuid: String): CompletableResult {
        return localSource.deleteImportedBook(uuid)
    }

    override suspend fun updateLastOpenedAt(uuid: String): CompletableResult {
        return localSource.updateLastOpenedAt(uuid)
    }
}

