package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileDeletionTransport
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [BookFileDeletionTransport::class])
class ParrotCloudBookFileDeletionTransport(
    @Provided private val service: ParrotCloudBookFileService,
) : BookFileDeletionTransport {
    override val serverId: String = PARROT_CLOUD_SERVER_ID

    override suspend fun delete(cloudBookFileId: String) {
        service.deleteBookFile(cloudBookFileId)
    }
}
