package com.retro99.books.data.source

import com.retro99.base.formatCurrentTime
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.model.LibraryBookJsonCodec
import com.retro99.books.data.model.LocalBookFileLocalModel
import com.retro99.books.data.model.toDomainModel
import com.retro99.books.data.model.toLibraryBookLocalModel
import com.retro99.books.data.model.toLocalModel
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBookMutation
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ImportedBooksLocalSource::class])
internal class ImportedBooksRoomDataSource(
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val databaseExecutor: DatabaseExecutor,
) : ImportedBooksLocalSource {

    override suspend fun saveImportedBook(book: BookDomainModel.LocalBook): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            val libraryBook = book.toLibraryBookLocalModel()
            if (libraryBook == null) {
                importedBooksDatabase.upsertImportedBook(book.toLocalModel())
            } else {
                val existing = libraryBooksDatabase.getLibraryBookById(
                    libraryBook.libraryBookId,
                )
                val intentionalReimport = existing?.deletedAt != null
                val mappedLibraryBook = if (intentionalReimport) {
                    libraryBook.copy(
                        cloudBookId = existing?.cloudBookId,
                        remoteRevision = existing?.remoteRevision,
                        metadataJson = existing?.metadataJson,
                    )
                } else {
                    libraryBook
                }
                importedBooksDatabase.upsertImportedBookWithLibraryMapping(
                    book = book.toLocalModel(),
                    mutation = LibraryBookMutation(
                        libraryBook = mappedLibraryBook,
                        localBookFile = LocalBookFileLocalModel(
                            libraryBookId = mappedLibraryBook.libraryBookId,
                            importedBookUuid = book.uuid,
                        ),
                        outboxEntry = SyncOutboxEntry.new(
                            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                            entityId = mappedLibraryBook.libraryBookId,
                            operation = SyncOutboxEntry.OPERATION_UPSERT,
                            payload = LibraryBookJsonCodec.encode(
                                book = mappedLibraryBook,
                                intentionalReimport = intentionalReimport,
                            ),
                            baseRevision = if (intentionalReimport) {
                                existing?.remoteRevision
                            } else {
                                null
                            },
                        ),
                        intentionalReimport = intentionalReimport,
                    ),
                )
            }
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

    override suspend fun getImportedBookByContentHash(
        contentHash: String,
    ): BookDomainModel.LocalBook? {
        return importedBooksDatabase.getImportedBookByContentHash(contentHash)?.toDomainModel()
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
