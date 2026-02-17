package com.retro99.books.data.source

import com.retro99.base.formatCurrentTime
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.model.toDomainModel
import com.retro99.books.data.model.toLocalModel
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ImportedBooksLocalSource::class])
internal class ImportedBooksRoomDataSource(
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val databaseExecutor: DatabaseExecutor,
) : ImportedBooksLocalSource {

    override suspend fun saveImportedBook(book: BookDomainModel.LocalBook): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            importedBooksDatabase.upsertImportedBook(book.toLocalModel())
        }
    }

    override fun observeAllImportedBooks(): Flow<List<BookDomainModel.LocalBook>> {
        return importedBooksDatabase.getAllImportedBooks().map { list ->
            list.map { it.toDomainModel() }
        }
    }

    override suspend fun getImportedBookByUuid(uuid: String): BookDomainModel.LocalBook? {
        return importedBooksDatabase.getImportedBookByUuid(uuid)?.toDomainModel()
    }

    override suspend fun deleteImportedBook(uuid: String): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            importedBooksDatabase.deleteImportedBook(uuid)
        }
    }

    override suspend fun updateLastOpenedAt(uuid: String): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            importedBooksDatabase.updateLastOpenedAt(uuid, formatCurrentTime())
        }
    }
}

