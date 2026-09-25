package com.retro99.books.ui.detail

import com.retro99.base.ui.BaseIntent
import com.retro99.books.domain.model.BookType
import com.retro99.library.domain.operation.ResolvedLibraryUploadTarget
import com.retro99.library.domain.projection.LibraryReaderTarget
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceResourceRef

sealed interface LibraryGroupDetailIntent : BaseIntent {
    data object OnBackClicked : LibraryGroupDetailIntent
    data object OnRetryClicked : LibraryGroupDetailIntent
    data class OnPreferredMediaSourceSelected(
        val sourceKey: SourceBookKey,
        val mediaType: String,
    ) : LibraryGroupDetailIntent
    data object OnManageMembersClicked : LibraryGroupDetailIntent
    data object OnManageMembersCancelled : LibraryGroupDetailIntent
    data class OnMemberSelectionChanged(
        val sourceKey: SourceBookKey,
        val selected: Boolean,
    ) : LibraryGroupDetailIntent
    data object OnSplitRequested : LibraryGroupDetailIntent
    data object OnSplitConfirmed : LibraryGroupDetailIntent
    data object OnSplitDismissed : LibraryGroupDetailIntent
    data class OnRemoveDeviceReplicaRequested(
        val sourceKey: SourceBookKey,
        val resource: SourceResourceRef,
    ) : LibraryGroupDetailIntent
    data object OnRemoveDeviceReplicaConfirmed : LibraryGroupDetailIntent
    data object OnRemoveDeviceReplicaDismissed : LibraryGroupDetailIntent
    data object OnRemoveDeviceReplicaRetried : LibraryGroupDetailIntent
    data object OnRemoveDeviceReplicaErrorDismissed : LibraryGroupDetailIntent
    data class OnDeleteRemoteReplicaRequested(
        val sourceKey: SourceBookKey,
        val resource: SourceResourceRef,
    ) : LibraryGroupDetailIntent
    data object OnDeleteRemoteReplicaConfirmed : LibraryGroupDetailIntent
    data object OnDeleteRemoteReplicaDismissed : LibraryGroupDetailIntent
    data object OnDeleteRemoteReplicaErrorDismissed : LibraryGroupDetailIntent
    data class OnRemoveRemoteBookRequested(
        val sourceKey: SourceBookKey,
    ) : LibraryGroupDetailIntent
    data object OnRemoveRemoteBookConfirmed : LibraryGroupDetailIntent
    data object OnRemoveRemoteBookDismissed : LibraryGroupDetailIntent
    data object OnRemoveRemoteBookErrorDismissed : LibraryGroupDetailIntent
    data class OnUploadRequested(
        val target: ResolvedLibraryUploadTarget,
    ) : LibraryGroupDetailIntent
    data class OnUploadRightsAttestationChanged(
        val attested: Boolean,
    ) : LibraryGroupDetailIntent
    data object OnUploadConfirmed : LibraryGroupDetailIntent
    data object OnUploadDismissed : LibraryGroupDetailIntent
    data class OnDownloadRequested(
        val target: LibraryGroupDownloadTarget,
    ) : LibraryGroupDetailIntent
    data class OnDownloadedResourceOpened(
        val target: LibraryGroupDownloadTarget,
    ) : LibraryGroupDetailIntent
    data class OnCachedMediaOpened(
        val target: LibraryGroupCachedMediaTarget,
    ) : LibraryGroupDetailIntent
    data class OnDeviceResourceOpenRequested(
        val target: LibraryReaderTarget,
        val bookType: BookType,
    ) : LibraryGroupDetailIntent
    data class OnCancelTransfer(
        val adapterId: LibraryAdapterId,
        val transferId: LibraryTransferId,
    ) : LibraryGroupDetailIntent
    data class OnRetryTransfer(
        val adapterId: LibraryAdapterId,
        val transferId: LibraryTransferId,
    ) : LibraryGroupDetailIntent
}
