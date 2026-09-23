package com.retro99.books.ui.detail

import com.retro99.base.ui.BaseIntent
import com.retro99.books.domain.model.BookType

sealed interface BookDetailIntent : BaseIntent {
    data object OnBackClicked : BookDetailIntent
    data object OnRetryClicked : BookDetailIntent
    data class OnTagClicked(val tagName: String) : BookDetailIntent
    data class OnSeriesClicked(val seriesUuid: String) : BookDetailIntent
    data object OnReadEbookClicked : BookDetailIntent
    data object OnPlayAudiobookClicked : BookDetailIntent
    data object OnReadReadaloudClicked : BookDetailIntent
    data class OnDownloadClicked(val bookType: BookType) : BookDetailIntent
    data class OnDeleteCacheClicked(val bookType: BookType) : BookDetailIntent
    data object OnDeleteCacheConfirmed : BookDetailIntent
    data object OnDeleteCacheDismissed : BookDetailIntent
    data object OnFavoriteClicked : BookDetailIntent
    data object OnDeleteLocalBookClicked : BookDetailIntent
    data object OnDeleteLocalBookConfirmed : BookDetailIntent
    data object OnDeleteLocalBookDismissed : BookDetailIntent
    data object OnBackupClicked : BookDetailIntent
    data class OnBackupAttestationChanged(val attested: Boolean) : BookDetailIntent
    data object OnBackupConfirmed : BookDetailIntent
    data object OnBackupDismissed : BookDetailIntent
    data class OnDeleteCloudBackupClicked(val bookType: BookType) : BookDetailIntent
    data object OnDeleteCloudBackupConfirmed : BookDetailIntent
    data object OnDeleteCloudBackupDismissed : BookDetailIntent
    data class OnCancelBookFileTransferClicked(val transferId: String) : BookDetailIntent
    data class OnRetryBookBackupClicked(val transferId: String) : BookDetailIntent
    data object OnUseLocalPositionClicked : BookDetailIntent
    data object OnUseRemotePositionClicked : BookDetailIntent
    data object OnConflictResolutionErrorDismissed : BookDetailIntent
    data object OnConflictDialogDismissed : BookDetailIntent
}
