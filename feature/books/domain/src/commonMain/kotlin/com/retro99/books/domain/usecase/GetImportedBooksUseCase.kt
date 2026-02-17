package com.retro99.books.domain.usecase

import com.retro99.books.domain.ImportedBooksRepository
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class GetImportedBooksUseCase(
    @Provided private val importedBooksRepository: ImportedBooksRepository,
) {
    operator fun invoke(): Flow<List<BookDomainModel.LocalBook>> {
        return importedBooksRepository.observeAllImportedBooks()
    }
}

