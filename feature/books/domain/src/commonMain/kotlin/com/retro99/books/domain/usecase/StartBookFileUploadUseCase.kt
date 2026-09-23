package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.UploadRightsAttestation
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class StartBookFileUploadUseCase(
    @Provided private val transferManager: BookFileTransferManager,
) {
    suspend operator fun invoke(
        serverId: String,
        localBookUuid: String,
        rightsAttestation: UploadRightsAttestation,
    ): String = transferManager.enqueueUpload(serverId, localBookUuid, rightsAttestation)
}
