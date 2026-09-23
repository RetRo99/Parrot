package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransferManager
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class RetryBookFileTransferUseCase(
    @Provided private val transferManager: BookFileTransferManager,
) {
    suspend operator fun invoke(transferId: String) = transferManager.retry(transferId)
}
