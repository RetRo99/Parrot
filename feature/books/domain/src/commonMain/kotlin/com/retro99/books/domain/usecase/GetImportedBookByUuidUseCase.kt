package com.retro99.books.domain.usecase

import com.retro99.base.result.AppResult
import com.retro99.books.domain.ImportedBooksRepository
import com.retro99.books.domain.model.BookDomainModel
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class GetImportedBookByUuidUseCase(
    @Provided private val importedBooksRepository: ImportedBooksRepository,
) {
    suspend operator fun invoke(uuid: String): AppResult<BookDomainModel.LocalBook> {
        return importedBooksRepository.getImportedBookByUuid(uuid)
    }
}

