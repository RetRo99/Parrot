package com.retro99.books.ui.detail

import com.retro99.base.result.AppError
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.DownloadState
import com.retro99.books.domain.BookFileTransfer
import com.retro99.library.domain.projection.LibraryBookGroup

data class BookDetailViewState(
    val book: BookUiModel? = null,
    val libraryGroup: LibraryBookGroup? = null,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val ebookDownloadState: DownloadState = DownloadState.Idle,
    val audiobookDownloadState: DownloadState = DownloadState.Idle,
    val readaloudDownloadState: DownloadState = DownloadState.Idle,
    val deleteConfirmationBookType: BookType? = null,
    val isFavorite: Boolean = false,
    val showDeleteLocalBookConfirmation: Boolean = false,
    val progressInfo: BookProgressInfoUiModel? = null,
    val isResolvingConflict: Boolean = false,
    val conflictResolutionError: AppError? = null,
    /** The book type the user wants to open, shown when there's a conflict to resolve first */
    val pendingOpenBookType: BookType? = null,
    val supportsBookBackup: Boolean = false,
    val supportsBookDeletion: Boolean = false,
    val cloudBackupDeleteConfirmationType: BookType? = null,
    val showBackupConfirmation: Boolean = false,
    val backupRightsAttested: Boolean = false,
    val bookFileTransfers: List<BookFileTransfer> = emptyList(),
    val bookFileTransferError: String? = null,
    val replaceBackupConfirmationTransferId: String? = null,
    val replacingBackupTransferId: String? = null,
)
