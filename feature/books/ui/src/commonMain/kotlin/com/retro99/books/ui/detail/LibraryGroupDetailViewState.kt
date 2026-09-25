package com.retro99.books.ui.detail

import com.retro99.library.domain.operation.ResolvedLibraryUploadTarget
import com.retro99.library.domain.operation.UnavailableLibraryUploadTarget
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.DownloadState
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceResourceRef

data class LibraryGroupDetailViewState(
    val group: LibraryBookGroup? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isSavingPreferredMediaSource: Boolean = false,
    val preferredMediaSourceError: Boolean = false,
    val isManagingMembers: Boolean = false,
    val managementGroupId: LibraryGroupId? = null,
    val selectedMemberKeys: Set<SourceBookKey> = emptySet(),
    val showSplitConfirmation: Boolean = false,
    val isSplittingMembers: Boolean = false,
    val splitSelectionError: Boolean = false,
    val replicaRemovalTarget: LibraryGroupDeviceReplicaRemovalTarget? = null,
    val showReplicaRemovalConfirmation: Boolean = false,
    val pendingReplicaRemoval: PendingLibraryGroupReplicaRemoval? = null,
    val isRemovingReplica: Boolean = false,
    val replicaRemovalError: String? = null,
    val replicaRemovalIsStale: Boolean = false,
    val availableDeviceReplicaRemovalTargets: Set<LibraryGroupDeviceReplicaRemovalTarget> =
        emptySet(),
    val isResolvingDeviceReplicaRemovalTargets: Boolean = false,
    val availableRemoteReplicaDeletionTargets:
        Set<LibraryGroupRemoteReplicaDeletionTarget> = emptySet(),
    val isResolvingRemoteReplicaDeletionTargets: Boolean = false,
    val pendingRemoteReplicaDeletionTarget: LibraryGroupRemoteReplicaDeletionTarget? = null,
    val showRemoteReplicaDeletionConfirmation: Boolean = false,
    val isDeletingRemoteReplica: Boolean = false,
    val remoteReplicaDeletionError: String? = null,
    val remoteReplicaDeletionIsStale: Boolean = false,
    val availableRemoteBookRemovalTargets: Set<LibraryGroupRemoteBookRemovalTarget> = emptySet(),
    val isResolvingRemoteBookRemovalTargets: Boolean = false,
    val pendingRemoteBookRemovalTarget: LibraryGroupRemoteBookRemovalTarget? = null,
    val showRemoteBookRemovalConfirmation: Boolean = false,
    val isRemovingRemoteBook: Boolean = false,
    val remoteBookRemovalError: String? = null,
    val remoteBookRemovalIsStale: Boolean = false,
    val remoteBookRemovalQueuedSourceKeys: Set<SourceBookKey> = emptySet(),
    val availableUploadTargets: List<ResolvedLibraryUploadTarget> = emptyList(),
    val unavailableUploadTargets: List<UnavailableLibraryUploadTarget> = emptyList(),
    val isResolvingUploadTargets: Boolean = false,
    val pendingUploadTarget: ResolvedLibraryUploadTarget? = null,
    val showUploadConfirmation: Boolean = false,
    val uploadRightsAttested: Boolean = false,
    val isExecutingUpload: Boolean = false,
    val uploadErrorMessage: String? = null,
    val availableDownloadTargets: List<LibraryGroupDownloadTarget> = emptyList(),
    val readerCacheTargets: List<LibraryGroupCachedMediaTarget> = emptyList(),
    val isResolvingDownloadTargets: Boolean = false,
    val isExecutingDownload: Boolean = false,
    val isPreparingReader: Boolean = false,
    val downloadErrorMessage: String? = null,
    val readingProgressBySourceAndMediaType: Map<SourceBookKey, Map<String, Double>> = emptyMap(),
    val downloadStates: Map<SourceResourceRef, DownloadState> = emptyMap(),
    val transfers: List<LibraryTransferProgress> = emptyList(),
    val isLoadingTransfers: Boolean = false,
    val transferErrorMessage: String? = null,
    val activeTransferAction: LibraryTransferActionKey? = null,
) {
    val canSplitSelectedMembers: Boolean
        get() {
            val currentGroup = group ?: return false
            val currentMemberKeys = currentGroup.members.map { member -> member.sourceKey }
            return isManagingMembers &&
                managementGroupId == currentGroup.groupId &&
                selectedMemberKeys.isNotEmpty() &&
                selectedMemberKeys.size < currentMemberKeys.size &&
                currentMemberKeys.containsAll(selectedMemberKeys) &&
                !isSplittingMembers
        }

    val selectedAllMembers: Boolean
        get() {
            val currentMemberKeys = group?.members?.map { member -> member.sourceKey }
                ?: return false
            return currentMemberKeys.isNotEmpty() && selectedMemberKeys == currentMemberKeys.toSet()
        }

    val canRetryReplicaRemoval: Boolean
        get() = pendingReplicaRemoval != null && replicaRemovalError != null &&
            !isRemovingReplica

    fun isTransferActionRunning(transfer: LibraryTransferProgress): Boolean =
        activeTransferAction == transfer.actionKey()
}

data class LibraryGroupDownloadTarget(
    val assetId: MediaAssetId,
    val target: LibraryOperationTarget.RemoteReplica,
    val bookType: BookType,
    val mediaType: String,
    val title: String,
    val supportsReaderCache: Boolean = false,
    val readerCacheBookId: String? = null,
)

data class LibraryTransferActionKey(
    val adapterId: LibraryAdapterId,
    val transferId: LibraryTransferId,
)

fun LibraryTransferProgress.actionKey(): LibraryTransferActionKey =
    LibraryTransferActionKey(adapterId, transferId)

data class LibraryGroupDeviceReplicaRemovalTarget(
    val groupId: LibraryGroupId,
    val sourceKey: SourceBookKey,
    val resource: SourceResourceRef,
    val replica: LibraryOperationTarget.DeviceReplica,
    val mediaType: String,
    val title: String,
)

data class LibraryGroupRemoteReplicaDeletionTarget(
    val groupId: LibraryGroupId,
    val sourceKey: SourceBookKey,
    val resource: SourceResourceRef,
    val target: LibraryOperationTarget.RemoteReplica,
    val mediaType: String,
    val title: String,
    val assetId: MediaAssetId,
)

data class LibraryGroupRemoteBookRemovalTarget(
    val groupId: LibraryGroupId,
    val sourceKey: SourceBookKey,
    val source: SourceBookRef,
    val sourceRevision: String?,
    val title: String,
    val observedResources: List<SourceMediaResource>,
) {
    init {
        require(source.key == sourceKey)
        require(title.isNotBlank())
    }
}

data class PendingLibraryGroupReplicaRemoval(
    val groupId: LibraryGroupId,
    val request: LibraryOperationRequest,
    val requestedAt: String,
)
