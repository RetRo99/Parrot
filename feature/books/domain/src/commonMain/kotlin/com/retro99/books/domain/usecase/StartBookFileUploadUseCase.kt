package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.toBookFileUploadAttestation
import com.retro99.cloudaccount.domain.usecase.GetCurrentUploadRightsAttestationUseCase
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class StartBookFileUploadUseCase(
    @Provided private val transferManager: BookFileTransferManager,
    @Provided private val getCurrentUploadRightsAttestationUseCase: GetCurrentUploadRightsAttestationUseCase,
) {
    suspend operator fun invoke(
        serverId: String,
        libraryBookId: String,
        mediaType: String,
        localProfileId: String,
    ): String {
        val attestation = getCurrentUploadRightsAttestationUseCase(localProfileId)
        return transferManager.enqueueUpload(
            serverId = serverId,
            libraryBookId = libraryBookId,
            mediaType = mediaType,
            rightsAttestation = attestation.toBookFileUploadAttestation(),
        )
    }
}
