package com.retro99.server.local

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.data.source.ImportedBooksLocalSource
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.LocalBookFileUsageCoordinator
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.OperationAvailability
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryOperationAdapter::class])
class LocalLibraryOperationAdapter(
    @Provided private val importedBooksLocalSource: ImportedBooksLocalSource,
    @Provided private val fileImportManager: FileImportManager,
    @Provided private val transferManager: BookFileTransferManager,
    @Provided private val fileUsageCoordinator: LocalBookFileUsageCoordinator,
) : LibraryOperationAdapter {
    override val adapterId = LibraryAdapterId("local")

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> {
        val replicaTarget = target as? LibraryOperationTarget.DeviceReplica
            ?: return listOf(unavailable("A local device replica is required"))
        if (replicaTarget.source.key.adapterId != adapterId) {
            return listOf(unavailable("This adapter does not own the selected replica"))
        }
        val lease = fileUsageCoordinator.tryAcquireRemoval(replicaTarget.storageRef.value)
            ?: return listOf(unavailable("The local file is currently in use"))
        return try {
            listOf(availabilityWhileLocked(replicaTarget))
        } finally {
            lease.release()
        }
    }

    private suspend fun availabilityWhileLocked(
        replicaTarget: LibraryOperationTarget.DeviceReplica,
    ): OperationAvailability {
        if (replicaTarget.source.key.adapterId != adapterId) {
            return unavailable("This adapter does not own the selected replica")
        }
        val bookUuid = replicaTarget.resource.nativeResourceId
        val localBook = importedBooksLocalSource.getImportedBookByUuid(bookUuid)
            ?: return available()
        if (localBook.origin !in REMOVABLE_ORIGINS) {
            return unavailable("This local book copy is not owned by the app")
        }
        if (localBook.filePath != replicaTarget.storageRef.value) {
            return unavailable("The selected device location has changed")
        }
        val libraryBookId = localBook.libraryBookId
            ?: return available()
        val activeTransfers = transferManager.observeForBook(
            PARROT_CLOUD_SERVER_ID,
            libraryBookId,
        ).first().filter { transfer ->
            transfer.state in ACTIVE_TRANSFER_STATES
        }
        if (activeTransfers.isNotEmpty()) {
            return unavailable("A transfer is still using or staging this book")
        }
        return available()
    }

    override suspend fun execute(
        request: LibraryOperationRequest,
    ): LibraryOperationResult {
        if (request.operation != LibraryOperation.RemoveDeviceReplica) {
            return LibraryOperationResult.Rejected("The local adapter does not support this action")
        }
        val target = request.target as? LibraryOperationTarget.DeviceReplica
            ?: return LibraryOperationResult.Rejected("A local device replica is required")
        val lease = fileUsageCoordinator.tryAcquireRemoval(target.storageRef.value)
            ?: return LibraryOperationResult.Rejected("The local file is currently in use")
        return try {
            val availability = availabilityWhileLocked(target)
            if (!availability.isAvailable) {
                return LibraryOperationResult.Rejected(
                    availability.reason ?: "The selected replica cannot be removed",
                )
            }
            val bookUuid = target.resource.nativeResourceId
            val localBook = importedBooksLocalSource.getImportedBookByUuid(bookUuid)
            if (localBook != null && localBook.origin !in REMOVABLE_ORIGINS) {
                return LibraryOperationResult.Rejected(
                    "This local book copy is not owned by the app",
                )
            }
            if (localBook != null && localBook.filePath != target.storageRef.value) {
                return LibraryOperationResult.Rejected(
                    "The selected device location has changed",
                )
            }
            if (
                target.deleteStorageBytes &&
                !fileImportManager.deleteImportedBookReplicaFile(
                    bookUuid,
                    target.storageRef.value,
                )
            ) {
                return LibraryOperationResult.Rejected(
                    "The imported media file could not be removed",
                )
            }
            if (localBook == null) {
                return LibraryOperationResult.Accepted(request.operationId)
            }
            val deletion = importedBooksLocalSource.deleteImportedBook(bookUuid)
            if (deletion.isOk) {
                LibraryOperationResult.Accepted(request.operationId)
            } else {
                LibraryOperationResult.Rejected(
                    "The imported book association could not be removed",
                )
            }
        } finally {
            lease.release()
        }
    }

    private fun available() = OperationAvailability(
        operation = LibraryOperation.RemoveDeviceReplica,
        isAvailable = true,
    )

    private fun unavailable(reason: String) = OperationAvailability(
        operation = LibraryOperation.RemoveDeviceReplica,
        isAvailable = false,
        reason = reason,
    )

    private companion object {
        const val ORIGIN_IMPORTED = "import"
        const val ORIGIN_CLOUD_DOWNLOAD = "cloud_download"
        val REMOVABLE_ORIGINS = setOf(ORIGIN_IMPORTED, ORIGIN_CLOUD_DOWNLOAD)
        val ACTIVE_TRANSFER_STATES = setOf("pending", "transferring", "verifying", "finalizing")
    }
}
