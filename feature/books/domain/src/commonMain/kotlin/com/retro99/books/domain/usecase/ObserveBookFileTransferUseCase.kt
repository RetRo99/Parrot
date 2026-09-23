package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class ObserveBookFileTransferUseCase(
    @Provided private val transferManager: BookFileTransferManager,
) {
    operator fun invoke(serverId: String, libraryBookId: String): Flow<List<BookFileTransfer>> =
        transferManager.observeForBook(serverId, libraryBookId)
}
