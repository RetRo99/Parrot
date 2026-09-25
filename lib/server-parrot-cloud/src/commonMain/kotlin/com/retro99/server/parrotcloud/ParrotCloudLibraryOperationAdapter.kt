package com.retro99.server.parrotcloud

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.StartBookFileUploadUseCase
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.isActiveFor
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryBookOperation
import com.retro99.server.api.library.LibraryBookOperationAvailability
import com.retro99.server.api.library.LibraryBookOperationRequest
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.LibraryTransferStatus
import com.retro99.server.api.library.LibraryUploadDestinationCandidate
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryOperationAdapter::class])
class ParrotCloudLibraryOperationAdapter(
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val cloudAccountRepository: CloudAccountRepository,
    @Provided private val uploadRightsAttestationRepository: UploadRightsAttestationRepository,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val transferManager: BookFileTransferManager,
    @Provided private val startBookFileUploadUseCase: StartBookFileUploadUseCase,
) : LibraryOperationAdapter {
    override val adapterId = LibraryAdapterId(PARROT_CLOUD_SERVER_ID)
    private val bookRemovalService = ParrotCloudBookRemovalService(
        libraryBooksDatabase = libraryBooksDatabase,
        syncOutboxDatabase = syncOutboxDatabase,
        cloudFilesDatabase = cloudFilesDatabase,
    )

    override suspend fun proposeUploadDestinations(
        source: LibraryUploadSource,
    ): List<LibraryUploadDestinationCandidate> {
        val sourceReplica = source.sourceReplica
        val sourceRef = sourceReplica.source
        if (!isLocalSource(sourceRef)) return emptyList()

        val localBook = importedBooksDatabase.getImportedBookByUuid(
            sourceReplica.resource.nativeResourceId,
        ) ?: return emptyList()
        if (localBook.filePath != sourceReplica.storageRef.value) return emptyList()
        if (!supportsEpubUpload(source.format, localBook.bookType)) return emptyList()

        val account = activeCloudAccount(sourceRef.key.profileId.value) ?: return emptyList()
        val target = LibraryOperationTarget.UploadDestination(
            source = sourceRef,
            resource = sourceReplica.resource,
            assetId = source.assetId,
            sourceReplicaId = sourceReplica.replicaId,
            sourceStorageRef = sourceReplica.storageRef,
            destinationAdapterId = adapterId,
            destinationAccount = account,
            destinationConnectionId = SourceConnectionId(PARROT_CLOUD_SERVER_ID),
        )
        return listOf(
            LibraryUploadDestinationCandidate(
                proposedBy = adapterId,
                source = source,
                target = target,
                acceptedFormat = source.format,
            ),
        )
    }

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> {
        return when (target) {
            is LibraryOperationTarget.UploadDestination -> {
                val reason = unavailableReason(target)
                listOf(availability(LibraryOperation.Upload, reason))
            }
            is LibraryOperationTarget.RemoteReplica -> {
                listOf(
                    availability(
                        LibraryOperation.Download,
                        unavailableDownloadReason(target),
                    ),
                    availability(
                        LibraryOperation.DeleteRemoteReplica,
                        unavailableDeleteReason(target),
                    ),
                )
            }
            else -> listOf(
                unavailable(
                    LibraryOperation.Upload,
                    "A Parrot Cloud upload destination is required",
                ),
            )
        }
    }

    override suspend fun bookAvailability(
        source: SourceBookRef,
        operation: LibraryBookOperation,
    ): LibraryBookOperationAvailability {
        val reason = if (operation == LibraryBookOperation.RemoveRemoteBook) {
            unavailableBookRemovalReason(source)
        } else {
            "Parrot Cloud does not support this book action"
        }
        return LibraryBookOperationAvailability(
            operation = operation,
            isAvailable = reason == null,
            reason = reason,
        )
    }

    override fun observeTransfers(
        source: SourceBookRef,
    ): Flow<List<LibraryTransferProgress>> = flow {
        val account = activeCloudAccount(source.key.profileId.value)
        if (account == null) {
            emit(emptyList())
            return@flow
        }
        val libraryBookId = when (source.key.adapterId) {
            LOCAL_ADAPTER_ID -> {
                if (!isLocalSource(source)) {
                    emit(emptyList())
                    return@flow
                }
                val localBook = importedBookForLocalSource(source)
                if (localBook == null) {
                    emit(emptyList())
                    return@flow
                }
                val hash = LocalContentIdentity.canonicalHash(localBook.contentHash)
                if (
                    localBook.contentHashAlgorithm != LocalContentIdentity.HASH_ALGORITHM ||
                    hash == null
                ) {
                    emit(emptyList())
                    return@flow
                }
                "${LocalContentIdentity.HASH_ALGORITHM}:$hash"
            }
            adapterId -> {
                if (source.key.accountIdentity != account ||
                    source.connectionId?.value != PARROT_CLOUD_SERVER_ID
                ) {
                    emit(emptyList())
                    return@flow
                }
                val legacyLibraryBookId = source.legacyLibraryBookId?.value
                if (legacyLibraryBookId == null) {
                    emit(emptyList())
                    return@flow
                }
                legacyLibraryBookId
            }
            else -> {
                emit(emptyList())
                return@flow
            }
        }
        emitAll(
            transferManager.observeForBook(PARROT_CLOUD_SERVER_ID, libraryBookId).map { transfers ->
                transfers.mapNotNull { transfer -> transfer.toLibraryProgress(source) }
            },
        )
    }

    override suspend fun cancelTransfer(transferId: LibraryTransferId) {
        transferManager.cancelTransfer(transferId.value)
    }

    override suspend fun retryTransfer(transferId: LibraryTransferId) {
        transferManager.retry(transferId.value)
    }

    override suspend fun execute(
        request: LibraryOperationRequest,
    ): LibraryOperationResult {
        if (request.operation == LibraryOperation.DeleteRemoteReplica) {
            val target = request.target as? LibraryOperationTarget.RemoteReplica
                ?: return LibraryOperationResult.Rejected(
                    "A Parrot Cloud remote replica is required for deletion",
                )
            if (!request.userConfirmed) {
                return LibraryOperationResult.Rejected(
                    "Confirm the remote file deletion before continuing",
                )
            }
            val reason = unavailableDeleteReason(target)
            if (reason != null) return LibraryOperationResult.Rejected(reason)

            return try {
                transferManager.deleteRemoteCloudFile(
                    serverId = PARROT_CLOUD_SERVER_ID,
                    libraryBookId = requireNotNull(target.source.legacyLibraryBookId).value,
                    cloudBookId = target.source.key.nativeBookId.value,
                    cloudBookFileId = target.remoteRef.value,
                    mediaType = requireNotNull(target.mediaType),
                )
                LibraryOperationResult.Accepted(request.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LibraryOperationResult.Rejected(
                    "The selected Cloud file could not be deleted",
                )
            }
        }
        if (request.operation == LibraryOperation.Download) {
            val target = request.target as? LibraryOperationTarget.RemoteReplica
                ?: return LibraryOperationResult.Rejected(
                    "A Parrot Cloud remote replica is required for download",
                )
            val reason = unavailableDownloadReason(target)
            if (reason != null) return LibraryOperationResult.Rejected(reason)

            return try {
                val libraryBookId = requireNotNull(target.source.legacyLibraryBookId).value
                transferManager.enqueueDownloadForCloudFile(
                    serverId = PARROT_CLOUD_SERVER_ID,
                    libraryBookId = libraryBookId,
                    cloudBookId = target.source.key.nativeBookId.value,
                    cloudBookFileId = target.remoteRef.value,
                )
                LibraryOperationResult.Accepted(request.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LibraryOperationResult.Rejected(
                    "The selected Cloud file could not be queued for download",
                )
            }
        }
        if (request.operation != LibraryOperation.Upload) {
            return LibraryOperationResult.Rejected("Parrot Cloud does not support this action")
        }
        val target = request.target as? LibraryOperationTarget.UploadDestination
            ?: return LibraryOperationResult.Rejected(
                "A Parrot Cloud upload destination is required",
            )
        if (!request.userConfirmed) {
            return LibraryOperationResult.Rejected(
                "Confirm the upload rights before starting this upload",
            )
        }
        val reason = unavailableReason(target)
        if (reason != null) return LibraryOperationResult.Rejected(reason)

        return try {
            if (uploadRightsAttestationRepository.requiresReattestation(
                    target.source.key.profileId.value,
                )
            ) {
                uploadRightsAttestationRepository.record(target.source.key.profileId.value)
            }
            val changedReason = unavailableReason(target)
            if (changedReason != null) {
                return LibraryOperationResult.Rejected(changedReason)
            }
            startBookFileUploadUseCase(
                serverId = target.destinationConnectionId.value,
                localBookUuid = target.resource.nativeResourceId,
                localProfileId = target.source.key.profileId.value,
            )
            LibraryOperationResult.Accepted(request.operationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LibraryOperationResult.Rejected("The selected book could not be queued for upload")
        }
    }

    override suspend fun executeBookOperation(
        request: LibraryBookOperationRequest,
    ): LibraryOperationResult {
        if (request.operation != LibraryBookOperation.RemoveRemoteBook) {
            return LibraryOperationResult.Rejected(
                "Parrot Cloud does not support this book action",
            )
        }
        if (!request.userConfirmed) {
            return LibraryOperationResult.Rejected(
                "Confirm the Cloud book removal before continuing",
            )
        }
        val reason = unavailableBookRemovalReason(request.source)
        if (reason != null) return LibraryOperationResult.Rejected(reason)

        return try {
            val libraryBookId = requireNotNull(request.source.legacyLibraryBookId).value
            val cloudBookId = request.source.key.nativeBookId.value
            cloudFilesDatabase.getFileStates(libraryBookId).forEach { file ->
                transferManager.deleteRemoteCloudFile(
                    serverId = PARROT_CLOUD_SERVER_ID,
                    libraryBookId = libraryBookId,
                    cloudBookId = cloudBookId,
                    cloudBookFileId = file.cloudBookFileId,
                    mediaType = file.mediaType,
                )
            }
            val deletion = bookRemovalService.deleteCloudBook(
                libraryBookId = libraryBookId,
                expectedCloudBookId = cloudBookId,
                expectedSourceRevision = request.expectedSourceRevision,
                cloudUserId = requireNotNull(
                    (request.source.key.accountIdentity as? SourceAccountIdentity.Portable)
                        ?.accountId,
                ),
            )
            if (deletion.isOk) {
                LibraryOperationResult.Accepted(request.operationId)
            } else {
                LibraryOperationResult.Rejected(
                    "The selected Cloud book could not be queued for removal",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LibraryOperationResult.Rejected(
                "The selected Cloud book could not be queued for removal",
            )
        }
    }

    private suspend fun unavailableBookRemovalReason(source: SourceBookRef): String? {
        if (source.key.adapterId != adapterId) {
            return "This adapter does not own the selected Cloud book"
        }
        if (source.connectionId?.value != PARROT_CLOUD_SERVER_ID) {
            return "The selected Parrot Cloud connection is no longer available"
        }
        val libraryBookId = source.legacyLibraryBookId?.value
            ?: return "The selected Cloud book has no library identity"
        val account = activeCloudAccount(source.key.profileId.value)
            ?: return "The current profile has no signed-in linked Parrot Cloud account"
        if (source.key.accountIdentity != account) {
            return "The selected Parrot Cloud account is no longer linked to this profile"
        }
        val book = libraryBooksDatabase.getLibraryBookById(libraryBookId)
            ?: return "The selected Cloud book is no longer available"
        if (book.cloudBookId != source.key.nativeBookId.value) {
            return "The selected Cloud book identity has changed"
        }
        if (book.deletedAt != null) {
            return "The selected Cloud book has already been removed"
        }
        if (book.remoteRevision == null) {
            return "The Cloud book has not finished syncing"
        }
        if (book.contentHash.isNullOrBlank()) {
            return "The Cloud book has no content identity"
        }
        val algorithm = book.contentHashAlgorithm ?: DEFAULT_CONTENT_HASH_ALGORITHM
        if (libraryBookId != "$algorithm:${book.contentHash}") {
            return "The selected Cloud book identity has changed"
        }
        val cloudFiles = cloudFilesDatabase.getFileStates(libraryBookId)
        if (hasActiveUpload(libraryBookId)) {
            return "Cloud uploads must finish before removing this book"
        }
        if (cloudFiles.any { file ->
                file.libraryBookId != libraryBookId ||
                    file.cloudBookId != book.cloudBookId ||
                    libraryBookId != "${file.contentHashAlgorithm}:${file.contentHash}" ||
                    file.cloudBookFileId.isBlank() ||
                    file.status !in DELETABLE_CLOUD_FILE_STATUSES
            }
        ) {
            return "Cloud file transfers must finish before removing this book"
        }
        if (cloudFiles.isNotEmpty() && !transferManager.supportsDeletion(PARROT_CLOUD_SERVER_ID)) {
            return "Parrot Cloud file deletion is unavailable"
        }
        return null
    }

    private suspend fun unavailableDownloadReason(
        target: LibraryOperationTarget.RemoteReplica,
    ): String? {
        val source = target.source
        if (source.key.adapterId != adapterId) {
            return "This adapter does not own the selected Cloud file"
        }
        if (source.connectionId?.value != PARROT_CLOUD_SERVER_ID) {
            return "The selected Parrot Cloud connection is no longer available"
        }
        if (target.remoteRef.value != target.resource.nativeResourceId ||
            target.replicaId.value != target.remoteRef.value
        ) {
            return "The selected Cloud file reference has changed"
        }
        val account = activeCloudAccount(source.key.profileId.value)
            ?: return "The current profile has no signed-in linked Parrot Cloud account"
        if (source.key.accountIdentity != account) {
            return "The selected Parrot Cloud account is no longer linked to this profile"
        }
        val libraryBookId = source.legacyLibraryBookId?.value
            ?: return "The selected Cloud book has no library identity"
        if (!transferManager.supportsDownload(PARROT_CLOUD_SERVER_ID)) {
            return "Parrot Cloud download is unavailable"
        }

        val matchingFiles = cloudFilesDatabase.getFileStates(libraryBookId).filter { file ->
            file.cloudBookFileId == target.remoteRef.value
        }
        if (matchingFiles.size != 1) {
            return "The selected Cloud file is no longer available"
        }
        val file = matchingFiles.single()
        if (file.libraryBookId != libraryBookId ||
            file.cloudBookId != source.key.nativeBookId.value ||
            libraryBookId != "${file.contentHashAlgorithm}:${file.contentHash}"
        ) {
            return "The selected Cloud file no longer matches this Cloud book"
        }
        if (file.relativePath.isNotEmpty() || file.status != FILE_STATUS_AVAILABLE) {
            return "The selected Cloud file is no longer available"
        }
        if (!supportsEpubBookType(file.mediaType) || file.sizeBytes <= 0L) {
            return "The selected Cloud file cannot be downloaded"
        }
        return null
    }

    private suspend fun unavailableDeleteReason(
        target: LibraryOperationTarget.RemoteReplica,
    ): String? {
        val source = target.source
        if (source.key.adapterId != adapterId) {
            return "This adapter does not own the selected Cloud file"
        }
        if (source.connectionId?.value != PARROT_CLOUD_SERVER_ID) {
            return "The selected Parrot Cloud connection is no longer available"
        }
        val cloudBookFileId = target.remoteRef.value
        if (target.resource.book != source.key ||
            target.resource.nativeResourceId != cloudBookFileId ||
            target.replicaId.value != cloudBookFileId
        ) {
            return "The selected Cloud file reference has changed"
        }
        val mediaType = target.mediaType
        if (mediaType.isNullOrBlank()) {
            return "The selected Cloud file has no media type"
        }
        val account = activeCloudAccount(source.key.profileId.value)
            ?: return "The current profile has no signed-in linked Parrot Cloud account"
        if (source.key.accountIdentity != account) {
            return "The selected Parrot Cloud account is no longer linked to this profile"
        }
        val libraryBookId = source.legacyLibraryBookId?.value
            ?: return "The selected Cloud book has no library identity"
        if (!transferManager.supportsDeletion(PARROT_CLOUD_SERVER_ID)) {
            return "Parrot Cloud file deletion is unavailable"
        }

        val matchingFiles = cloudFilesDatabase.getFileStates(libraryBookId).filter { file ->
            file.cloudBookFileId == cloudBookFileId
        }
        if (matchingFiles.size != 1) {
            return "The selected Cloud file is no longer available"
        }
        val file = matchingFiles.single()
        if (file.libraryBookId != libraryBookId ||
            file.cloudBookId != source.key.nativeBookId.value ||
            !file.mediaType.equals(mediaType, ignoreCase = true) ||
            libraryBookId != "${file.contentHashAlgorithm}:${file.contentHash}"
        ) {
            return "The selected Cloud file no longer matches this Cloud book"
        }
        if (file.status !in DELETABLE_CLOUD_FILE_STATUSES) {
            return "The selected Cloud file is no longer available"
        }
        if (hasActiveUpload(libraryBookId)) {
            return "Cloud uploads must finish before deleting Cloud files"
        }
        if (target.resource.revision != null &&
            target.resource.revision != file.remoteRevision.toString()
        ) {
            return "The selected Cloud file revision has changed"
        }
        return null
    }

    private suspend fun unavailableReason(
        target: LibraryOperationTarget.UploadDestination,
    ): String? {
        if (target.destinationAdapterId != adapterId) {
            return "This adapter does not own the selected upload destination"
        }
        if (target.destinationConnectionId.value != PARROT_CLOUD_SERVER_ID) {
            return "The selected Parrot Cloud connection is no longer available"
        }
        if (!isLocalSource(target.source)) {
            return "A local imported book is required for upload"
        }
        val account = activeCloudAccount(target.source.key.profileId.value)
            ?: return "The current profile has no signed-in linked Parrot Cloud account"
        if (account != target.destinationAccount) {
            return "The selected Parrot Cloud account is no longer linked to this profile"
        }
        if (!transferManager.supportsUpload(PARROT_CLOUD_SERVER_ID)) {
            return "Parrot Cloud upload is unavailable"
        }
        val localBook = importedBooksDatabase.getImportedBookByUuid(
            target.resource.nativeResourceId,
        ) ?: return "The selected local book is no longer available"
        if (localBook.filePath != target.sourceStorageRef.value) {
            return "The selected local storage replica has changed"
        }
        if (!supportsEpubBookType(localBook.bookType)) {
            return "Only imported EPUB books can be uploaded to Parrot Cloud"
        }
        return null
    }

    private suspend fun activeCloudAccount(
        localProfileId: String,
    ): SourceAccountIdentity.Portable? {
        if (userRegistry.getActiveProfileIdOrDefault() != localProfileId) return null
        val link = profileLinkRepository.getForLocalProfile(localProfileId) ?: return null
        if (link.localProfileId != localProfileId) return null
        if (!link.isActiveFor(cloudAccountRepository.currentAuthState())) return null
        return SourceAccountIdentity.Portable(
            backendId = PARROT_CLOUD_BACKEND_ID,
            accountId = link.cloudUserId,
        )
    }

    private suspend fun hasActiveUpload(libraryBookId: String): Boolean =
        transferManager.observeForBook(PARROT_CLOUD_SERVER_ID, libraryBookId)
            .first()
            .any { transfer ->
                transfer.direction.equals(UPLOAD_DIRECTION, ignoreCase = true) &&
                    transfer.state.lowercase() in ACTIVE_UPLOAD_STATES
            }

    private fun isLocalSource(source: SourceBookRef): Boolean {
        val localConnection = SourceConnectionId(LOCAL_SERVER_ID)
        if (source.key.adapterId != LOCAL_ADAPTER_ID || source.connectionId != localConnection) {
            return false
        }
        return when (val identity = source.key.accountIdentity) {
            is SourceAccountIdentity.Unresolved -> identity.connectionId == localConnection
            is SourceAccountIdentity.Portable ->
                identity.backendId == LocalContentIdentity.BACKEND_ID &&
                    identity.accountId == LocalContentIdentity.ACCOUNT_ID &&
                    LocalContentIdentity.isPortableNativeBookId(source.key.nativeBookId.value)
        }
    }

    private suspend fun importedBookForLocalSource(source: SourceBookRef) = when (
        val identity = source.key.accountIdentity
    ) {
        is SourceAccountIdentity.Unresolved -> {
            if (!isLocalSource(source)) {
                null
            } else {
                importedBooksDatabase.getImportedBookByUuid(source.key.nativeBookId.value)
            }
        }

        is SourceAccountIdentity.Portable -> {
            if (!isLocalSource(source)) {
                null
            } else {
                val hash = source.key.nativeBookId.value
                    .removePrefix("${LocalContentIdentity.HASH_ALGORITHM}:")
                importedBooksDatabase.getImportedBookByContentHash(hash)?.takeIf { book ->
                    book.contentHashAlgorithm == LocalContentIdentity.HASH_ALGORITHM &&
                        LocalContentIdentity.canonicalHash(book.contentHash) == hash
                }
            }
        }
    }

    private fun supportsEpubUpload(format: String, bookType: String): Boolean {
        if (!supportsEpubBookType(bookType)) return false
        return format.equals("epub", ignoreCase = true) ||
            format.equals(bookType, ignoreCase = true)
    }

    private fun supportsEpubBookType(bookType: String): Boolean =
        bookType.equals(BookType.EBOOK.value, ignoreCase = true) ||
            bookType.equals(BookType.READALOUD.value, ignoreCase = true)

    private fun com.retro99.books.domain.BookFileTransfer.toLibraryProgress(
        source: SourceBookRef,
    ): LibraryTransferProgress? {
        val operation = when (direction.lowercase()) {
            "upload" -> LibraryOperation.Upload
            "download" -> LibraryOperation.Download
            else -> return null
        }
        val status = when (state.lowercase()) {
            "pending" -> LibraryTransferStatus.Queued
            "transferring" -> LibraryTransferStatus.Transferring
            "verifying" -> LibraryTransferStatus.Verifying
            "finalizing" -> LibraryTransferStatus.Finalizing
            "completed" -> LibraryTransferStatus.Completed
            "failed" -> LibraryTransferStatus.Failed
            "cancelled" -> LibraryTransferStatus.Cancelled
            else -> LibraryTransferStatus.Unknown
        }
        val isActive = status in ACTIVE_TRANSFER_STATUSES
        return LibraryTransferProgress(
            adapterId = adapterId,
            source = source,
            transferId = LibraryTransferId(transferId),
            operation = operation,
            status = status,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            attemptCount = attemptCount,
            canCancel = isActive,
            canRetry = status == LibraryTransferStatus.Failed,
        )
    }

    private fun availability(
        operation: LibraryOperation,
        reason: String?,
    ) = if (reason == null) {
        OperationAvailability(operation, isAvailable = true)
    } else {
        unavailable(operation, reason)
    }

    private fun unavailable(
        operation: LibraryOperation,
        reason: String,
    ) = OperationAvailability(
        operation = operation,
        isAvailable = false,
        reason = reason,
    )

    private companion object {
        const val PARROT_CLOUD_BACKEND_ID = "parrot-cloud"
        const val FILE_STATUS_AVAILABLE = "available"
        const val FILE_STATUS_DELETING = "deleting"
        const val UPLOAD_DIRECTION = "upload"
        const val DEFAULT_CONTENT_HASH_ALGORITHM = "sha-256-v1"
        val DELETABLE_CLOUD_FILE_STATUSES = setOf(
            FILE_STATUS_AVAILABLE,
            FILE_STATUS_DELETING,
        )
        val LOCAL_ADAPTER_ID = LibraryAdapterId("local")
        val ACTIVE_UPLOAD_STATES = setOf(
            "pending",
            "transferring",
            "verifying",
            "finalizing",
        )
        val ACTIVE_TRANSFER_STATUSES = setOf(
            LibraryTransferStatus.Queued,
            LibraryTransferStatus.Transferring,
            LibraryTransferStatus.Verifying,
            LibraryTransferStatus.Finalizing,
        )
    }
}
