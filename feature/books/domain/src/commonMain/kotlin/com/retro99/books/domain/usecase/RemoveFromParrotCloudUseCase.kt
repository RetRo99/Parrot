package com.retro99.books.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.DeviceLibraryRepository
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Removes a library book's files from Parrot Cloud. Copies on this device become imports
 * first, so the removal that follows doesn't delete them here.
 */
@Factory
class RemoveFromParrotCloudUseCase(
    @Provided private val deviceLibraryRepository: DeviceLibraryRepository,
    @Provided private val transferManager: BookFileTransferManager,
) {
    suspend operator fun invoke(libraryBookId: String, mediaTypes: List<String>) {
        deviceLibraryRepository.keepDeviceFilesAsImports(libraryBookId).getOrElse { error ->
            throw IllegalStateException("Could not keep this book's device files: $error")
        }
        mediaTypes.forEach { mediaType ->
            transferManager.deleteRemoteBackup(PARROT_CLOUD_SERVER_ID, libraryBookId, mediaType)
        }
    }
}
