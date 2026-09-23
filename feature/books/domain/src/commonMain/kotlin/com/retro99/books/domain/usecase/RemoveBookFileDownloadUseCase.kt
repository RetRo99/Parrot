package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransferManager
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class RemoveBookFileDownloadUseCase(
    @Provided private val transferManager: BookFileTransferManager,
) {
    suspend operator fun invoke(serverId: String, libraryBookId: String, mediaType: String) =
        transferManager.removeDownload(serverId, libraryBookId, mediaType)
}
