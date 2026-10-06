package com.retro99.books.ui.detail

import com.retro99.base.result.AppError
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.LinkedCopyUiModel
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.DownloadState
import com.retro99.books.domain.BookFileTransfer
import com.retro99.reader.domain.linked.LinkedResumeOffer

data class BookDetailViewState(
    val book: BookUiModel? = null,
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
    val positionConflict: com.retro99.reader.domain.model.ReadingProgressResult.Conflict? = null,
    val conflictServerName: String = "",
    /** What this device calls itself: "This phone", "This iPhone", "This tablet". */
    val thisDeviceName: String = "",
    /** The conflict side being applied right now; shows the progress on its card. */
    val resolvingConflictSide: com.retro99.books.ui.components.ConflictSide? = null,
    /** The book type the user wants to open, shown when there's a conflict to resolve first */
    val pendingOpenBookType: BookType? = null,
    /** Whether "Add to Parrot Cloud" is shown: some media type of a library book can go. */
    val supportsBookBackup: Boolean = false,
    /** Actions per media type of a library book, from [libraryActions]. */
    val libraryMediaActions: Map<BookType, LibraryMediaActions> = emptyMap(),
    /** Whole-book actions of a library book, from [bookActions]. Null for server books. */
    val libraryBookActions: LibraryBookActions? = null,
    /** Media types whose device copy the user can remove. */
    val removableDownloadTypes: Set<BookType> = emptySet(),
    val showRemoveFromParrotConfirmation: Boolean = false,
    val showBackupConfirmation: Boolean = false,
    val backupRightsAttested: Boolean = false,
    val bookFileTransfers: List<BookFileTransfer> = emptyList(),
    val bookFileTransferError: String? = null,
    val replaceBackupConfirmationTransferId: String? = null,
    val replacingBackupTransferId: String? = null,
    /** The other copies this book is linked to, shown under "Also in". */
    val linkedCopies: List<LinkedCopyUiModel> = emptyList(),
    /** The copy the user is about to unlink with "Not the same book". */
    val unlinkConfirmationCopy: LinkedCopyUiModel? = null,
    /** Another linked copy was read more recently: offered before opening (§1.3). */
    val linkedResumeOffer: LinkedResumeOffer? = null,
    val comparingLinkedPositions: Boolean = false,
    val pendingListenMode: Boolean = false,
    val parrotActive: Boolean = false,
)
