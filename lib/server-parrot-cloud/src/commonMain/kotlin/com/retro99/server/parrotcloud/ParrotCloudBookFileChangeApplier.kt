package com.retro99.server.parrotcloud

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryEvidenceProvenance
import com.retro99.database.api.library.LibraryEvidenceProvenanceKind
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.user.api.UserRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

/** Applies the server-owned file lifecycle feed to the per-profile local mirror. */
@Single
class ParrotCloudBookFileChangeApplier(
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val bookFileTransferManager: BookFileTransferManager,
    @Provided private val libraryEvidenceDatabase: LibraryEvidenceDatabase? = null,
    @Provided private val librarySourceSnapshotsDatabase: LibrarySourceSnapshotsDatabase? = null,
    @Provided private val userRegistry: UserRegistry? = null,
    @Provided private val authenticatedRepositoryProvider: AuthenticatedRepositoryProvider? = null,
    @Provided private val libraryGroupsDatabase: LibraryGroupsDatabase? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun apply(payload: JsonElement, feedRevision: Long? = null) {
        val file = json.decodeFromJsonElement<ParrotCloudBookFilePayload>(payload)
        val cloudBookId = file.cloudBookId ?: return
        val libraryBook = libraryBooksDatabase.getLibraryBookByCloudBookId(cloudBookId)
        if (libraryBook == null) {
            cloudFilesDatabase.enqueuePendingFileFeedChange(
                cloudBookId = cloudBookId,
                feedRevision = feedRevision,
                payloadJson = payload.toString(),
                receivedAt = Clock.System.now().toString(),
            )
            return
        }
        applyResolved(file, libraryBook)
    }

    /** Replays feed events retained before their Cloud metadata was synced locally. */
    suspend fun applyPendingForCloudBook(cloudBookId: String) {
        val libraryBook = libraryBooksDatabase.getLibraryBookByCloudBookId(cloudBookId) ?: return
        cloudFilesDatabase.getPendingFileFeedChanges(cloudBookId).forEach { pending ->
            val payload = json.parseToJsonElement(pending.payloadJson)
            val file = json.decodeFromJsonElement<ParrotCloudBookFilePayload>(payload)
            applyResolved(file, libraryBook)
            cloudFilesDatabase.deletePendingFileFeedChange(pending.id)
        }
    }

    /** Retry queued feed events after process restart or interrupted metadata application. */
    suspend fun replayPending() {
        cloudFilesDatabase.getPendingFileFeedCloudBookIds().forEach { cloudBookId ->
            applyPendingForCloudBook(cloudBookId)
        }
    }

    /** Reassociates completed transfers using persisted file state after account linking. */
    suspend fun reconcileCompletedTransferEvidence(localProfileId: String) {
        if (libraryEvidenceDatabase == null) return
        val profileId = LibraryProfileId(localProfileId)
        cloudFilesDatabase.getTransfers(
            serverId = PARROT_CLOUD_SERVER_ID,
            states = listOf("completed"),
        ).forEach { transfer ->
            val cloudBookId = transfer.cloudBookId ?: return@forEach
            val cloudBookFileId = transfer.cloudBookFileId ?: return@forEach
            val libraryBook = libraryBooksDatabase.getLibraryBookByCloudBookId(cloudBookId)
                ?: return@forEach
            if (libraryBook.libraryBookId != transfer.libraryBookId) return@forEach
            val file = cloudFilesDatabase.getFileStates(transfer.libraryBookId)
                .firstOrNull { state ->
                    state.cloudBookFileId == cloudBookFileId && state.status == "available"
                } ?: return@forEach
            val payload = file.toPayload()
            if (transfer.matches(payload, libraryBook)) {
                recordCompletedTransferEvidence(
                    file = payload,
                    libraryBook = libraryBook,
                    profileIdOverride = profileId,
                    includeAuthenticatedRepositoryIdentity = false,
                )
            }
        }
    }

    private suspend fun applyLifecycle(file: ParrotCloudBookFilePayload) {
        val removed = file.status == "none" || file.status == "removed"
        val removalInProgress = removed || file.status == "deleting"
        file.cloudBookFileId?.let { fileId ->
            if (file.invalidationReason == MANDATORY_INVALIDATION_REASON) {
                bookFileTransferManager.invalidateCloudFile(fileId)
            } else if (removalInProgress) {
                bookFileTransferManager.cancelDownloadsForCloudFile(fileId)
            }
        }
    }

    private suspend fun applyResolved(
        file: ParrotCloudBookFilePayload,
        libraryBook: LibraryBookEntity,
    ) {
        val mediaType = file.mediaType ?: return
        val relativePath = file.relativePath.orEmpty()
        val cloudBookId = file.cloudBookId ?: return
        if (isStale(file, libraryBook.libraryBookId, mediaType, relativePath)) return
        applyLifecycle(file)
        if (file.status == "none" || file.status == "removed") {
            retireRemoteResource(file, cloudBookId)
            cloudFilesDatabase.deleteFileState(libraryBook.libraryBookId, mediaType, relativePath)
            return
        }
        val fileId = file.cloudBookFileId ?: return
        cloudFilesDatabase.upsertFileState(
            CloudBookFileEntity(
                libraryBookId = libraryBook.libraryBookId,
                cloudBookId = cloudBookId,
                cloudBookFileId = fileId,
                mediaType = mediaType,
                relativePath = relativePath,
                fileName = file.fileName.orEmpty(),
                status = file.status.orEmpty(),
                sizeBytes = file.sizeBytes ?: 0,
                contentHash = file.contentHash.orEmpty(),
                contentHashAlgorithm = file.contentHashAlgorithm.orEmpty(),
                remoteRevision = file.remoteRevision ?: 0,
                updatedAt = Clock.System.now().toString(),
            ),
        )
        recordCompletedTransferEvidence(file, libraryBook)
    }

    private suspend fun retireRemoteResource(
        file: ParrotCloudBookFilePayload,
        cloudBookId: String,
    ) {
        val snapshotsDatabase = librarySourceSnapshotsDatabase ?: return
        val cloudBookFileId = file.cloudBookFileId ?: return
        val profileId = LibraryProfileId(
            userRegistry?.getActiveProfileIdOrDefault() ?: UserRegistry.DEFAULT_USER_ID,
        )
        val accountIdentities = cloudAccountIdentityCandidates(profileId, cloudBookId)
        if (accountIdentities.size > 1) return
        val source = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId(CLOUD_ADAPTER_ID),
            accountIdentity = accountIdentities.singleOrNull()
                ?: SourceAccountIdentity.Unresolved(SourceConnectionId(PARROT_CLOUD_SERVER_ID)),
            nativeBookId = NativeBookId(cloudBookId),
        )
        snapshotsDatabase.retireRemoteReplica(
            resource = SourceResourceRef(source, cloudBookFileId),
            expectedRemoteRef = RemoteResourceRef(cloudBookFileId),
        )
    }

    private suspend fun authenticatedCloudAccountIdentity(): SourceAccountIdentity.Portable? =
        authenticatedRepositoryProvider
            ?.getBooksRepository(PARROT_CLOUD_SERVER_ID)
            ?.takeIf { repository ->
                repository.serverId == PARROT_CLOUD_SERVER_ID &&
                    repository.libraryAdapterId == LibraryAdapterId(CLOUD_ADAPTER_ID)
            }
            ?.libraryAccountIdentity()

    private suspend fun cloudAccountIdentityCandidates(
        profileId: LibraryProfileId,
        cloudBookId: String,
        includeAuthenticatedRepositoryIdentity: Boolean = true,
    ): List<SourceAccountIdentity.Portable> {
        val membershipIdentities = libraryGroupsDatabase
            ?.getAllMemberships(profileId)
            .orEmpty()
            .asSequence()
            .filter { membership ->
                val source = membership.source
                val connectionId = source.connectionId
                source.key.adapterId.value == CLOUD_ADAPTER_ID &&
                    source.key.nativeBookId.value == cloudBookId &&
                    (connectionId == null || connectionId.value == PARROT_CLOUD_SERVER_ID)
            }
            .mapNotNull { membership ->
                membership.source.key.accountIdentity as? SourceAccountIdentity.Portable
            }
            .filter { identity -> identity.backendId == CLOUD_ADAPTER_ID }
            .distinct()
            .toList()
        val repositoryIdentity = if (includeAuthenticatedRepositoryIdentity) {
            authenticatedCloudAccountIdentity()
        } else {
            null
        }
        return (membershipIdentities + listOfNotNull(repositoryIdentity)).distinct()
    }

    private suspend fun isStale(
        file: ParrotCloudBookFilePayload,
        libraryBookId: String,
        mediaType: String,
        relativePath: String,
    ): Boolean {
        val existing = cloudFilesDatabase.getFileStates(libraryBookId).firstOrNull { state ->
            state.mediaType == mediaType && state.relativePath == relativePath
        } ?: return false
        val incomingRevision = file.remoteRevision ?: 0L
        if (incomingRevision < existing.remoteRevision) return true
        return incomingRevision == existing.remoteRevision && existing.status == "available" &&
            (file.status == "upload_pending" || file.status == "uploading" ||
                file.status == "upload_failed")
    }

    private suspend fun recordCompletedTransferEvidence(
        file: ParrotCloudBookFilePayload,
        libraryBook: LibraryBookEntity,
        profileIdOverride: LibraryProfileId? = null,
        includeAuthenticatedRepositoryIdentity: Boolean = true,
    ) {
        val evidenceDatabase = libraryEvidenceDatabase ?: return
        if (file.status != "available") return
        val cloudBookId = file.cloudBookId ?: return
        val cloudBookFileId = file.cloudBookFileId ?: return
        if (file.contentHash.isNullOrBlank() || file.contentHashAlgorithm.isNullOrBlank()) return
        cloudFilesDatabase.getTransfersForCloudFile(cloudBookFileId)
            .filter { transfer -> transfer.matches(file, libraryBook) }
            .forEach { transfer ->
                val localBookUuid = transfer.localSourceUuid ?: return@forEach
                recordCompletedTransferEvidence(
                    evidenceDatabase = evidenceDatabase,
                    transfer = transfer,
                    cloudBookId = cloudBookId,
                    cloudBookFileId = cloudBookFileId,
                    localBookUuid = localBookUuid,
                    profileIdOverride = profileIdOverride,
                    includeAuthenticatedRepositoryIdentity = includeAuthenticatedRepositoryIdentity,
                )
            }
    }

    private suspend fun recordCompletedTransferEvidence(
        evidenceDatabase: LibraryEvidenceDatabase,
        transfer: CloudFileTransferEntity,
        cloudBookId: String,
        cloudBookFileId: String,
        localBookUuid: String,
        profileIdOverride: LibraryProfileId?,
        includeAuthenticatedRepositoryIdentity: Boolean,
    ) {
        val profileId = profileIdOverride ?: LibraryProfileId(
            userRegistry?.getActiveProfileIdOrDefault() ?: UserRegistry.DEFAULT_USER_ID,
        )
        val unresolvedCloudIdentity = SourceAccountIdentity.Unresolved(
            SourceConnectionId(transfer.serverId),
        )
        val localKey = localSourceKeyForResource(profileId, localBookUuid)
            ?: SourceBookKey(
                profileId = profileId,
                adapterId = LibraryAdapterId(LOCAL_ADAPTER_ID),
                accountIdentity = SourceAccountIdentity.Unresolved(
                    SourceConnectionId(LOCAL_SERVER_ID),
                ),
                nativeBookId = NativeBookId(localBookUuid),
            )
        val previousEvidence = evidenceDatabase.getActiveEvidence(profileId)
            .asSequence()
            .mapNotNull { record ->
                val completed = record.evidence as? SourceIdentityEvidence.CompletedTransfer
                    ?: return@mapNotNull null
                if (completed.transferId.value != transfer.transferId ||
                    !completed.hasSameNativeEndpoints(
                        localBookUuid = localBookUuid,
                        cloudBookId = cloudBookId,
                        cloudBookFileId = cloudBookFileId,
                    )
                ) return@mapNotNull null
                record to completed
            }
            .toList()
        val previousCloudIdentity = previousEvidence.firstNotNullOfOrNull { (_, completed) ->
            completed.cloudBookKey(CLOUD_ADAPTER_ID)?.accountIdentity
                as? SourceAccountIdentity.Portable
        }
        val accountCandidates = cloudAccountIdentityCandidates(
            profileId = profileId,
            cloudBookId = cloudBookId,
            includeAuthenticatedRepositoryIdentity = includeAuthenticatedRepositoryIdentity,
        )
        val portableIdentities =
            (listOfNotNull(previousCloudIdentity) + accountCandidates).distinct()
        if (portableIdentities.size > 1) return
        val selectedCloudIdentity = portableIdentities.singleOrNull() ?: unresolvedCloudIdentity
        val cloudKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId(CLOUD_ADAPTER_ID),
            accountIdentity = selectedCloudIdentity,
            nativeBookId = NativeBookId(cloudBookId),
        )
        val localResource = SourceResourceRef(localKey, localBookUuid)
        val cloudResource = SourceResourceRef(cloudKey, cloudBookFileId)
        val source = if (transfer.direction == "upload") localResource else cloudResource
        val destination = if (transfer.direction == "upload") cloudResource else localResource
        val evidence = SourceIdentityEvidence.CompletedTransfer(
            transferId = LibraryTransferId(transfer.transferId),
            source = source,
            destination = destination,
        )
        val evidenceId = transferEvidenceId(
            transferId = transfer.transferId,
            accountIdentity = selectedCloudIdentity,
            source = source,
            destination = destination,
        )
        previousEvidence.forEach { (record, previous) ->
            if (record.evidenceId != evidenceId && previous.sameAssociationAs(evidence)) {
                evidenceDatabase.retireEvidence(profileId, record.evidenceId)
            }
        }
        evidenceDatabase.recordEvidence(
            LibraryEvidenceRecord(
                evidenceId = evidenceId,
                evidence = evidence,
                provenance = LibraryEvidenceProvenance(
                    kind = LibraryEvidenceProvenanceKind.Transfer,
                    referenceId = transfer.transferId,
                ),
                observedAt = transfer.createdAt,
            ),
        )
    }

    private suspend fun localSourceKeyForResource(
        profileId: LibraryProfileId,
        localBookUuid: String,
    ): SourceBookKey? {
        val snapshotsDatabase = librarySourceSnapshotsDatabase ?: return null
        val matches = snapshotsDatabase.getSnapshots(profileId).filter { snapshot ->
            snapshot.source.key.adapterId.value == LOCAL_ADAPTER_ID &&
                snapshot.source.connectionId?.value == LOCAL_SERVER_ID &&
                snapshot.resources.any { resource ->
                    resource.reference.nativeResourceId == localBookUuid
                }
        }
        return matches.singleOrNull()?.source?.key
    }

    private fun transferEvidenceId(
        transferId: String,
        accountIdentity: SourceAccountIdentity,
        source: SourceResourceRef,
        destination: SourceResourceRef,
    ): String {
        val identityPart = when (accountIdentity) {
            is SourceAccountIdentity.Portable -> buildString {
                append("portable:")
                appendComponent(accountIdentity.backendId)
                appendComponent(accountIdentity.accountId)
            }
            is SourceAccountIdentity.Unresolved -> buildString {
                append("unresolved:")
                appendComponent(accountIdentity.connectionId.value)
            }
        }
        return buildString {
            append("$TRANSFER_EVIDENCE_PREFIX${transferId.length}:$transferId:$identityPart")
            append("source:")
            appendResourceIdentity(source)
            append("destination:")
            appendResourceIdentity(destination)
        }
    }

    private fun StringBuilder.appendComponent(value: String) {
        append(value.length)
        append(':')
        append(value)
        append(':')
    }

    private fun StringBuilder.appendResourceIdentity(resource: SourceResourceRef) {
        appendComponent(resource.book.adapterId.value)
        when (val identity = resource.book.accountIdentity) {
            is SourceAccountIdentity.Portable -> {
                append("portable:")
                appendComponent(identity.backendId)
                appendComponent(identity.accountId)
            }
            is SourceAccountIdentity.Unresolved -> {
                append("unresolved:")
                appendComponent(identity.connectionId.value)
            }
        }
        appendComponent(resource.book.nativeBookId.value)
        appendComponent(resource.nativeResourceId)
        val revision = resource.revision
        if (revision == null) {
            append("-1:")
        } else {
            appendComponent(revision)
        }
    }

    private fun SourceIdentityEvidence.CompletedTransfer.cloudBookKey(
        cloudAdapterId: String,
    ): SourceBookKey? = listOf(source.book, destination.book).firstOrNull { book ->
        book.adapterId.value == cloudAdapterId
    }

    private fun SourceIdentityEvidence.CompletedTransfer.hasSameNativeEndpoints(
        localBookUuid: String,
        cloudBookId: String,
        cloudBookFileId: String,
    ): Boolean = listOf(source, destination).any { resource ->
        resource.book.adapterId.value == LOCAL_ADAPTER_ID &&
            resource.nativeResourceId == localBookUuid
    } && listOf(source, destination).any { resource ->
        resource.book.adapterId.value == CLOUD_ADAPTER_ID &&
            resource.book.nativeBookId.value == cloudBookId &&
            resource.nativeResourceId == cloudBookFileId
    }

    private fun SourceIdentityEvidence.CompletedTransfer.sameAssociationAs(
        other: SourceIdentityEvidence.CompletedTransfer,
    ): Boolean {
        val local = listOf(source, destination).singleOrNull { resource ->
            resource.book.adapterId.value == LOCAL_ADAPTER_ID
        } ?: return false
        val otherLocal = listOf(other.source, other.destination).singleOrNull { resource ->
            resource.book.adapterId.value == LOCAL_ADAPTER_ID
        } ?: return false
        val cloud = listOf(source, destination).singleOrNull { resource ->
            resource.book.adapterId.value == CLOUD_ADAPTER_ID
        } ?: return false
        val otherCloud = listOf(other.source, other.destination).singleOrNull { resource ->
            resource.book.adapterId.value == CLOUD_ADAPTER_ID
        } ?: return false
        return transferId == other.transferId &&
            local.nativeResourceId == otherLocal.nativeResourceId &&
            cloud.book.nativeBookId == otherCloud.book.nativeBookId &&
            cloud.nativeResourceId == otherCloud.nativeResourceId
    }
}

@Serializable
private data class ParrotCloudBookFilePayload(
    @SerialName("cloud_book_id")
    val cloudBookId: String? = null,
    @SerialName("cloud_book_file_id")
    val cloudBookFileId: String? = null,
    @SerialName("media_type")
    val mediaType: String? = null,
    @SerialName("relative_path")
    val relativePath: String? = null,
    @SerialName("file_name")
    val fileName: String? = null,
    val status: String? = null,
    @SerialName("size_bytes")
    val sizeBytes: Long? = null,
    @SerialName("content_hash")
    val contentHash: String? = null,
    @SerialName("content_hash_algorithm")
    val contentHashAlgorithm: String? = null,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
    @SerialName("invalidation_reason")
    val invalidationReason: String? = null,
)

private fun CloudBookFileEntity.toPayload() = ParrotCloudBookFilePayload(
    cloudBookId = cloudBookId,
    cloudBookFileId = cloudBookFileId,
    mediaType = mediaType,
    relativePath = relativePath,
    fileName = fileName,
    status = status,
    sizeBytes = sizeBytes,
    contentHash = contentHash,
    contentHashAlgorithm = contentHashAlgorithm,
    remoteRevision = remoteRevision,
)

private const val MANDATORY_INVALIDATION_REASON = "mandatory_invalidation"
private const val LOCAL_ADAPTER_ID = "local"
private const val CLOUD_ADAPTER_ID = "parrot-cloud"
private const val TRANSFER_EVIDENCE_PREFIX = "completed-transfer:"

private fun CloudFileTransferEntity.matches(
    file: ParrotCloudBookFilePayload,
    libraryBook: LibraryBookEntity,
): Boolean =
    state == "completed" &&
        (direction == "upload" || direction == "download") &&
        serverId == PARROT_CLOUD_SERVER_ID &&
        cloudBookId == file.cloudBookId &&
        cloudBookId == libraryBook.cloudBookId &&
        cloudBookFileId == file.cloudBookFileId &&
        mediaType.equals(file.mediaType, ignoreCase = true) &&
        contentHash == file.contentHash &&
        contentHashAlgorithm == file.contentHashAlgorithm
