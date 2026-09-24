package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.toBookFileUploadAttestation
import com.retro99.cloudaccount.domain.usecase.GetCurrentUploadRightsAttestationUseCase
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class BackupAllBooksUseCase(
    @Provided private val transferManager: BookFileTransferManager,
    @Provided private val getCurrentUploadRightsAttestationUseCase: GetCurrentUploadRightsAttestationUseCase,
) {
    suspend operator fun invoke(
        serverId: String,
        localProfileId: String,
    ): Int {
        val attestation = getCurrentUploadRightsAttestationUseCase(localProfileId)
        return transferManager.backupAll(serverId, attestation.toBookFileUploadAttestation())
    }
}
