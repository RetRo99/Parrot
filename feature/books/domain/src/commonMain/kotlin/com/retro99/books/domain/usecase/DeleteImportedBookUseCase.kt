package com.retro99.books.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.ImportedBooksRepository
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DeleteImportedBookUseCase(
    @Provided private val importedBooksRepository: ImportedBooksRepository,
) {
    suspend operator fun invoke(uuid: String): CompletableResult {
        return importedBooksRepository.deleteImportedBook(uuid)
    }
}

