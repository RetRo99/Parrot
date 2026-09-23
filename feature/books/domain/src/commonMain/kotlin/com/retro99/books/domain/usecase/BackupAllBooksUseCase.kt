package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.UploadRightsAttestation
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class BackupAllBooksUseCase(
    @Provided private val transferManager: BookFileTransferManager,
) {
    suspend operator fun invoke(
        serverId: String,
        rightsAttestation: UploadRightsAttestation,
    ): Int = transferManager.backupAll(serverId, rightsAttestation)
}
