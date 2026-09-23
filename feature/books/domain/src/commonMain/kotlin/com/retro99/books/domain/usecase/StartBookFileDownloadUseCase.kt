package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransferManager
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class StartBookFileDownloadUseCase(
    @Provided private val transferManager: BookFileTransferManager,
) {
    suspend operator fun invoke(serverId: String, libraryBookId: String, mediaType: String): String =
        transferManager.enqueueDownload(serverId, libraryBookId, mediaType)
}
