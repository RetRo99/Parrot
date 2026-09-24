package com.retro99.books.ui.detail

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.base.result.log
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.ServerType
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.usecase.CancelBookFileTransferUseCase
import com.retro99.books.domain.usecase.ObserveBookFileTransferUseCase
import com.retro99.books.domain.usecase.RetryBookFileTransferUseCase
import com.retro99.books.domain.usecase.StartBookFileUploadUseCase
import com.retro99.books.domain.usecase.StartBookFileDownloadUseCase
import com.retro99.books.domain.usecase.RemoveBookFileDownloadUseCase
import com.retro99.books.domain.usecase.ObserveFavoriteUseCase
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.books.ui.model.toUiModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.DownloadState
import com.retro99.reader.domain.usecase.CancelDownloadUseCase
import com.retro99.reader.domain.usecase.DeleteMediaCacheUseCase
import com.retro99.reader.domain.usecase.DownloadMediaUseCase
import com.retro99.reader.domain.usecase.ObserveBookWithProgressUseCase
import com.retro99.reader.domain.usecase.ObserveDownloadStateUseCase
import com.retro99.reader.domain.usecase.ResolvePositionConflictUseCase
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class BookDetailViewModel(
    @InjectedParam private val serverId: String,
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val onNavigateToReader: (serverId: String, bookUuid: String, bookType: BookType, bookTitle: String) -> Unit,
    @InjectedParam private val onNavigateToSeriesDetail: (seriesUuid: String, seriesName: String) -> Unit,
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val observeBookWithProgressUseCase: ObserveBookWithProgressUseCase,
    @Provided private val downloadMediaUseCase: DownloadMediaUseCase,
    @Provided private val cancelDownloadUseCase: CancelDownloadUseCase,
    @Provided private val observeDownloadStateUseCase: ObserveDownloadStateUseCase,
    @Provided private val deleteMediaCacheUseCase: DeleteMediaCacheUseCase,
    @Provided private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    @Provided private val observeFavoriteUseCase: ObserveFavoriteUseCase,
    @Provided private val resolvePositionConflictUseCase: ResolvePositionConflictUseCase,
    @Provided private val fileImportManager: FileImportManager,
    @Provided private val bookFileTransferManager: BookFileTransferManager,
    @Provided private val observeBookFileTransferUseCase: ObserveBookFileTransferUseCase,
    @Provided private val startBookFileUploadUseCase: StartBookFileUploadUseCase,
    @Provided private val cancelBookFileTransferUseCase: CancelBookFileTransferUseCase,
    @Provided private val retryBookFileTransferUseCase: RetryBookFileTransferUseCase,
    @Provided private val startBookFileDownloadUseCase: StartBookFileDownloadUseCase,
    @Provided private val removeBookFileDownloadUseCase: RemoveBookFileDownloadUseCase,
    @Provided private val uploadRightsAttestationRepository: UploadRightsAttestationRepository,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val analytics: Analytics,
) : BaseViewModel<BookDetailViewState, BookDetailIntent>(
    BookDetailViewState(),
) {
    private var transferObservationJob: Job? = null
    private var observedTransferKey: String? = null

    init {
        analytics.logEvent(
            BookAnalyticsEvent.BookDetailViewed(
                bookUuid = bookUuid,
                source = "direct",
            )
        )
        observeBookWithProgress()
        observeDownloadStates()
        observeFavoriteState()
    }

    override fun onIntent(intent: BookDetailIntent) {
        when (intent) {
            BookDetailIntent.OnBackClicked -> {
                onBack()
            }

            BookDetailIntent.OnRetryClicked -> {
                observeBookWithProgress()
            }

            is BookDetailIntent.OnTagClicked -> {
                // TODO: Navigate to tag filter
            }

            is BookDetailIntent.OnSeriesClicked -> {
                val series = viewState.value.book?.series?.find { it.uuid == intent.seriesUuid }
                if (series != null) {
                    onNavigateToSeriesDetail(series.uuid, series.name)
                }
            }

            BookDetailIntent.OnReadEbookClicked -> {
                handleReadClick(BookType.EBOOK)
            }

            BookDetailIntent.OnPlayAudiobookClicked -> {
                handleReadClick(BookType.AUDIOBOOK)
            }

            BookDetailIntent.OnReadReadaloudClicked -> {
                handleReadClick(BookType.READALOUD)
            }

            is BookDetailIntent.OnDownloadClicked -> {
                toggleDownload(intent.bookType)
            }

            is BookDetailIntent.OnDeleteCacheClicked -> {
                updateState { it.copy(deleteConfirmationBookType = intent.bookType) }
            }

            BookDetailIntent.OnDeleteCacheConfirmed -> {
                viewState.value.deleteConfirmationBookType?.let { bookType ->
                    deleteMediaCache(bookType)
                }
                updateState { it.copy(deleteConfirmationBookType = null) }
            }

            BookDetailIntent.OnDeleteCacheDismissed -> {
                updateState { it.copy(deleteConfirmationBookType = null) }
            }

            BookDetailIntent.OnFavoriteClicked -> {
                toggleFavorite()
            }

            BookDetailIntent.OnDeleteLocalBookClicked -> {
                updateState { it.copy(showDeleteLocalBookConfirmation = true) }
            }

            BookDetailIntent.OnDeleteLocalBookConfirmed -> {
                deleteLocalBook()
                updateState { it.copy(showDeleteLocalBookConfirmation = false) }
            }

            BookDetailIntent.OnDeleteLocalBookDismissed -> {
                updateState { it.copy(showDeleteLocalBookConfirmation = false) }
            }

            BookDetailIntent.OnBackupClicked -> {
                updateState {
                    it.copy(
                        showBackupConfirmation = true,
                        backupRightsAttested = false,
                        bookFileTransferError = null,
                    )
                }
            }

            is BookDetailIntent.OnBackupAttestationChanged -> {
                updateState { it.copy(backupRightsAttested = intent.attested) }
            }

            BookDetailIntent.OnBackupConfirmed -> startBookBackup()

            BookDetailIntent.OnBackupDismissed -> {
                updateState { it.copy(showBackupConfirmation = false, backupRightsAttested = false) }
            }

            is BookDetailIntent.OnDeleteCloudBackupClicked -> {
                updateState { it.copy(cloudBackupDeleteConfirmationType = intent.bookType) }
            }

            BookDetailIntent.OnDeleteCloudBackupConfirmed -> deleteCloudBackup()

            BookDetailIntent.OnDeleteCloudBackupDismissed -> {
                updateState { it.copy(cloudBackupDeleteConfirmationType = null) }
            }

            is BookDetailIntent.OnCancelBookFileTransferClicked -> cancelBookTransfer(intent.transferId)

            is BookDetailIntent.OnRetryBookBackupClicked -> retryBookBackup(intent.transferId)

            is BookDetailIntent.OnReplaceBackupClicked -> {
                val transfer = viewState.value.bookFileTransfers.firstOrNull {
                    it.transferId == intent.transferId
                }
                if (transfer?.direction == "upload" &&
                    transfer.state == "failed" &&
                    transfer.lastError == "file_exists"
                ) {
                    updateState { it.copy(replaceBackupConfirmationTransferId = intent.transferId) }
                }
            }

            BookDetailIntent.OnReplaceBackupConfirmed -> replaceCloudBackup()

            BookDetailIntent.OnReplaceBackupDismissed -> {
                updateState { it.copy(replaceBackupConfirmationTransferId = null) }
            }

            BookDetailIntent.OnUseLocalPositionClicked -> {
                resolveConflictWithLocal()
            }

            BookDetailIntent.OnUseRemotePositionClicked -> {
                resolveConflictWithRemote()
            }

            BookDetailIntent.OnConflictResolutionErrorDismissed -> {
                updateState { it.copy(conflictResolutionError = null) }
            }

            BookDetailIntent.OnConflictDialogDismissed -> {
                updateState { it.copy(pendingOpenBookType = null) }
            }
        }
    }

    private fun deleteLocalBook() {
        viewModelScope.launch {
            fileImportManager.deleteLocalBook(bookUuid)
                .onSuccess {
                    // Navigate back after successful deletion
                    onBack()
                }
                .onFailure { error ->
                    error.log(analytics, "BookDetailViewModel: Failed to delete local book")
                }
        }
    }

    private fun resolveConflictWithLocal() {
        viewModelScope.launch {
            val pendingBookType = viewState.value.pendingOpenBookType
            val bookTitle = viewState.value.book?.title ?: ""
            updateState { it.copy(isResolvingConflict = true, conflictResolutionError = null) }
            resolvePositionConflictUseCase.useLocal(serverId, bookUuid)
                .onSuccess {
                    // Navigate to reader if user was trying to open a book
                    pendingBookType?.let { bookType ->
                        updateState { it.copy(pendingOpenBookType = null) }
                        navigateToReader(bookType, bookTitle)
                    }
                }
                .onFailure { error ->
                    error.log(analytics, "BookDetailViewModel: Failed to resolve conflict with local")
                    updateState { it.copy(conflictResolutionError = error, pendingOpenBookType = null) }
                }
            updateState { it.copy(isResolvingConflict = false) }
        }
    }

    private fun resolveConflictWithRemote() {
        viewModelScope.launch {
            val pendingBookType = viewState.value.pendingOpenBookType
            val bookTitle = viewState.value.book?.title ?: ""
            updateState { it.copy(isResolvingConflict = true, conflictResolutionError = null) }
            resolvePositionConflictUseCase.useRemote(serverId, bookUuid)
                .onSuccess {
                    // Navigate to reader if user was trying to open a book
                    pendingBookType?.let { bookType ->
                        updateState { it.copy(pendingOpenBookType = null) }
                        navigateToReader(bookType, bookTitle)
                    }
                }
                .onFailure { error ->
                    error.log(analytics, "BookDetailViewModel: Failed to resolve conflict with remote")
                    updateState { it.copy(conflictResolutionError = error, pendingOpenBookType = null) }
                }
            updateState { it.copy(isResolvingConflict = false) }
        }
    }

    private fun toggleFavorite() {
        val currentIsFavorite = viewState.value.isFavorite
        // Log the new state (opposite of current)
        analytics.logEvent(
            BookAnalyticsEvent.FavoriteToggled(
                bookUuid = bookUuid,
                isFavorite = !currentIsFavorite,
                source = "detail",
            )
        )
        viewModelScope.launch {
            toggleFavoriteUseCase(bookUuid)
        }
    }

    private fun observeFavoriteState() {
        observeFavoriteUseCase(bookUuid)
            .onEach { isFavorite ->
                updateState { it.copy(isFavorite = isFavorite) }
            }
            .launchIn(viewModelScope)
    }

    private fun handleReadClick(bookType: BookType) {
        analytics.logEvent(
            BookAnalyticsEvent.ReadButtonClicked(
                bookUuid = bookUuid,
                bookType = bookType.name.lowercase(),
            )
        )

        val currentState = viewState.value
        val downloadState = when (bookType) {
            BookType.EBOOK -> currentState.ebookDownloadState
            BookType.AUDIOBOOK -> currentState.audiobookDownloadState
            BookType.READALOUD -> currentState.readaloudDownloadState
        }

        if (downloadState is DownloadState.Cached) {
            // Check for conflict - show dialog for user to resolve first
            if (currentState.progressInfo?.hasConflict == true) {
                updateState { it.copy(pendingOpenBookType = bookType) }
            } else {
                val bookTitle = currentState.book?.title ?: ""
                navigateToReader(bookType, bookTitle)
            }
        }
        // If not cached, user should click download first
    }

    /**
     * Observes book data with progress information.
     * Uses database-driven reactivity for reliable updates even when the screen is recreated.
     * This replaces the separate fetchBook() and observeProgressChanges() methods.
     */
    private fun observeBookWithProgress() {
        observeBookWithProgressUseCase(serverId, bookUuid)
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { result ->
                result
                    .onSuccess { bookWithProgress ->
                        val uiModel = bookWithProgress.book.toUiModel()
                        observeBookFileTransfers(uiModel)
                        updateState {
                            it.copy(
                                book = uiModel,
                                supportsBookBackup = canBackUp(uiModel),
                                supportsBookDeletion = canDeleteCloudBackup(uiModel),
                                progressInfo = bookWithProgress.progressInfo?.toUiModel(),
                                isLoading = false,
                                error = null,
                            ).withCloudTransferStates()
                        }
                    }
                    .onFailure { error ->
                        error.log(analytics, "BookDetailViewModel: Failed to load book details")
                        updateState {
                            it.copy(
                                isLoading = false,
                                error = error,
                            )
                        }
                    }
            }
            .launchIn(viewModelScope)
    }

    private fun canBackUp(book: BookUiModel): Boolean =
        book is BookUiModel.StorytellerBook &&
            book.serverType == ServerType.ParrotCloud &&
            book.libraryBookId != null &&
            book.localSourceUuid != null &&
            book.mediaResources.any { resource -> resource.localPath != null } &&
            bookFileTransferManager.supportsUpload(serverId)

    private fun canDeleteCloudBackup(book: BookUiModel): Boolean =
        book is BookUiModel.StorytellerBook &&
            book.serverType == ServerType.ParrotCloud &&
            book.libraryBookId != null &&
            bookFileTransferManager.supportsDeletion(serverId)

    private fun deleteCloudBackup() {
        val bookType = viewState.value.cloudBackupDeleteConfirmationType ?: return
        val book = viewState.value.book as? BookUiModel.StorytellerBook ?: return
        val libraryBookId = book.libraryBookId ?: return
        updateState { it.copy(cloudBackupDeleteConfirmationType = null) }
        viewModelScope.launch {
            try {
                bookFileTransferManager.deleteRemoteBackup(serverId, libraryBookId, bookType.value)
            } catch (exception: CancellationException) {
                throw exception
            } catch (error: Exception) {
                updateState { it.copy(bookFileTransferError = error.message ?: "Could not delete cloud backup") }
            }
        }
    }

    private fun observeBookFileTransfers(book: BookUiModel) {
        val cloudBook = book as? BookUiModel.StorytellerBook
        val libraryBookId = cloudBook?.libraryBookId
        val key = libraryBookId?.let { "$serverId:$it" }
        if (key == observedTransferKey) return
        observedTransferKey = key
        transferObservationJob?.cancel()
        if (libraryBookId == null) {
            updateState { it.copy(bookFileTransfers = emptyList()) }
            return
        }
        transferObservationJob = observeBookFileTransferUseCase(serverId, libraryBookId)
            .onEach { transfers -> updateState { it.copy(bookFileTransfers = transfers).withCloudTransferStates() } }
            .launchIn(viewModelScope)
    }

    private fun startBookBackup() {
        val book = viewState.value.book as? BookUiModel.StorytellerBook ?: return
        val sourceUuid = book.localSourceUuid ?: return
        if (!viewState.value.backupRightsAttested || !viewState.value.supportsBookBackup) return
        viewModelScope.launch {
            try {
                val localProfileId = userRegistry.getActiveProfileIdOrDefault()
                recordUploadAttestationIfRequired(localProfileId)
                startBookFileUploadUseCase(
                    serverId = serverId,
                    localBookUuid = sourceUuid,
                    localProfileId = localProfileId,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState {
                    it.copy(bookFileTransferError = exception.message ?: "Could not start backup")
                }
            }
            updateState { it.copy(showBackupConfirmation = false, backupRightsAttested = false) }
        }
    }

    private suspend fun recordUploadAttestationIfRequired(localProfileId: String) {
        if (uploadRightsAttestationRepository.requiresReattestation(localProfileId)) {
            uploadRightsAttestationRepository.record(localProfileId)
        }
    }

    private fun cancelBookTransfer(transferId: String) {
        viewModelScope.launch {
            runCatching { cancelBookFileTransferUseCase(transferId) }
                .onFailure { error -> updateState { it.copy(bookFileTransferError = error.message) } }
        }
    }

    private fun retryBookBackup(transferId: String) {
        viewModelScope.launch {
            runCatching { retryBookFileTransferUseCase(transferId) }
                .onFailure { error -> updateState { it.copy(bookFileTransferError = error.message) } }
        }
    }

    private fun replaceCloudBackup() {
        val transferId = viewState.value.replaceBackupConfirmationTransferId ?: return
        val transfer = viewState.value.bookFileTransfers.firstOrNull { item ->
            item.transferId == transferId &&
                item.direction == "upload" &&
                item.state == "failed" &&
                item.lastError == "file_exists"
        } ?: run {
            updateState { it.copy(replaceBackupConfirmationTransferId = null) }
            return
        }
        updateState {
            it.copy(
                replaceBackupConfirmationTransferId = null,
                replacingBackupTransferId = transferId,
                bookFileTransferError = null,
            )
        }
        viewModelScope.launch {
            try {
                bookFileTransferManager.deleteRemoteBackup(
                    serverId = serverId,
                    libraryBookId = transfer.libraryBookId,
                    mediaType = transfer.mediaType,
                )
                // Retry the persisted upload so its original rights attestation is
                // retained. The delete and retry steps can safely be resumed.
                retryBookFileTransferUseCase(transferId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (error: Exception) {
                updateState {
                    it.copy(bookFileTransferError = error.message ?: "Could not replace cloud backup")
                }
            } finally {
                updateState {
                    if (it.replacingBackupTransferId == transferId) {
                        it.copy(replacingBackupTransferId = null)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun observeDownloadStates() {
        combine(
            observeDownloadStateUseCase(bookUuid, BookType.EBOOK),
            observeDownloadStateUseCase(bookUuid, BookType.AUDIOBOOK),
            observeDownloadStateUseCase(bookUuid, BookType.READALOUD),
        ) { ebookState, audiobookState, readaloudState ->
            Triple(ebookState, audiobookState, readaloudState)
        }
            .onEach { (ebookState, audiobookState, readaloudState) ->
                val previousState = viewState.value
                trackDownloadStateChange(
                    BookType.EBOOK,
                    previousState.ebookDownloadState,
                    ebookState,
                )
                trackDownloadStateChange(
                    BookType.AUDIOBOOK,
                    previousState.audiobookDownloadState,
                    audiobookState,
                )
                trackDownloadStateChange(
                    BookType.READALOUD,
                    previousState.readaloudDownloadState,
                    readaloudState,
                )
                updateState {
                    it.copy(
                        ebookDownloadState = ebookState,
                        audiobookDownloadState = audiobookState,
                        readaloudDownloadState = readaloudState,
                    ).withCloudTransferStates()
                }
            }
            .launchIn(viewModelScope)
    }

    private fun trackDownloadStateChange(
        bookType: BookType,
        previousState: DownloadState,
        newState: DownloadState,
    ) {
        // Only track transitions from Downloading state
        if (previousState !is DownloadState.Downloading) return

        when (newState) {
            is DownloadState.Cached -> {
                analytics.logEvent(
                    BookAnalyticsEvent.BookDownloadCompleted(
                        bookUuid = bookUuid,
                        downloadDurationMs = 0L, // Duration not tracked at this level
                    )
                )
            }

            is DownloadState.Failed -> {
                analytics.logEvent(
                    BookAnalyticsEvent.BookDownloadFailed(
                        bookUuid = bookUuid,
                        errorType = "download_failed",
                    )
                )
            }

            else -> { /* No tracking needed for other transitions */
            }
        }
    }

    private fun toggleDownload(bookType: BookType) {
        val book = viewState.value.book ?: return
        val currentState = when (bookType) {
            BookType.EBOOK -> viewState.value.ebookDownloadState
            BookType.AUDIOBOOK -> viewState.value.audiobookDownloadState
            BookType.READALOUD -> viewState.value.readaloudDownloadState
        }

        // If currently downloading, cancel it
        if (currentState is DownloadState.Downloading) {
            analytics.logEvent(
                BookAnalyticsEvent.BookDownloadCancelled(bookUuid = bookUuid)
            )
            val cloudTransfer = cloudDownloadTransfer(bookType)
            viewModelScope.launch {
                if (cloudTransfer != null) {
                    cancelBookFileTransferUseCase(cloudTransfer.transferId)
                } else {
                    cancelDownloadUseCase(bookUuid, bookType)
                }
            }
            return
        }

        val cloudBook = book as? BookUiModel.StorytellerBook
        val cloudResource = cloudBook?.mediaResources?.firstOrNull { resource ->
            resource.mediaType.equals(bookType.value, ignoreCase = true)
        }
        if (cloudBook?.serverType == ServerType.ParrotCloud &&
            bookType != BookType.AUDIOBOOK &&
            cloudBook.libraryBookId != null &&
            cloudResource?.cloudBookFileId != null &&
            cloudResource.remoteAvailability == "Available" &&
            cloudResource.localPath == null &&
            bookFileTransferManager.supportsDownload(serverId)
        ) {
            viewModelScope.launch {
                runCatching {
                    startBookFileDownloadUseCase(serverId, cloudBook.libraryBookId, bookType.value)
                }.onFailure { error ->
                    updateState { it.copy(bookFileTransferError = error.message ?: "Could not restore book") }
                }
            }
            return
        }

        // Otherwise, start the download
        val filePath = book.filePath(bookType) ?: return

        analytics.logEvent(
            BookAnalyticsEvent.BookDownloadStarted(
                bookUuid = bookUuid,
                bookType = bookType.name.lowercase(),
            )
        )
        viewModelScope.launch {
            downloadMediaUseCase(bookUuid, bookType, filePath, book.title, serverId)
        }
    }

    private fun deleteMediaCache(bookType: BookType) {
        analytics.logEvent(
            BookAnalyticsEvent.BookCacheDeleted(
                bookUuid = bookUuid,
                bookType = bookType.name.lowercase(),
            )
        )
        viewModelScope.launch {
            val cloudBook = viewState.value.book as? BookUiModel.StorytellerBook
            val restoredResource = cloudBook?.mediaResources?.firstOrNull { resource ->
                resource.mediaType.equals(bookType.value, ignoreCase = true) &&
                    resource.localOrigin == "cloud_download"
            }
            val hasCompletedRestore = viewState.value.bookFileTransfers.any { transfer ->
                transfer.direction == "download" &&
                    transfer.mediaType.equals(bookType.value, ignoreCase = true) &&
                    transfer.state == "completed"
            }
            if (cloudBook?.serverType == ServerType.ParrotCloud &&
                cloudBook.libraryBookId != null && (restoredResource != null || hasCompletedRestore)
            ) {
                removeBookFileDownloadUseCase(serverId, cloudBook.libraryBookId, bookType.value)
            } else {
                deleteMediaCacheUseCase(bookUuid, bookType)
            }
            // Download state will be updated via the observer
        }
    }

    private fun cloudDownloadTransfer(bookType: BookType) = viewState.value.bookFileTransfers
        .firstOrNull { transfer ->
            transfer.direction == "download" &&
                transfer.mediaType.equals(bookType.value, ignoreCase = true) &&
                transfer.state in setOf("pending", "transferring", "verifying", "finalizing")
        }

    private fun navigateToReader(bookType: BookType, bookTitle: String) {
        val cloudBook = viewState.value.book as? BookUiModel.StorytellerBook
        val localUuid = cloudBook?.takeIf { it.serverType == ServerType.ParrotCloud }?.localSourceUuid
        if (localUuid != null) {
            onNavigateToReader(LOCAL_SERVER_ID, localUuid, bookType, bookTitle)
        } else {
            onNavigateToReader(serverId, bookUuid, bookType, bookTitle)
        }
    }

    private fun BookDetailViewState.withCloudTransferStates(): BookDetailViewState {
        val cloudBook = book as? BookUiModel.StorytellerBook ?: return this
        if (cloudBook.serverType != ServerType.ParrotCloud) return this

        fun stateFor(bookType: BookType, fallback: DownloadState): DownloadState {
            val resource = cloudBook.mediaResources.firstOrNull {
                it.mediaType.equals(bookType.value, ignoreCase = true)
            }
            if (resource?.localPath != null) return DownloadState.Cached
            val transfer = bookFileTransfers.firstOrNull { item ->
                item.direction == "download" && item.mediaType.equals(bookType.value, ignoreCase = true)
            } ?: return fallback
            return when (transfer.state) {
                "pending" -> DownloadState.Downloading(0f)
                "transferring" -> DownloadState.Downloading(
                    if (transfer.totalBytes > 0) {
                        (transfer.bytesTransferred.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
                    } else null,
                )
                "verifying", "finalizing" -> DownloadState.Downloading(1f)
                "completed" -> DownloadState.Cached
                else -> fallback
            }
        }

        return copy(
            ebookDownloadState = stateFor(BookType.EBOOK, ebookDownloadState),
            audiobookDownloadState = stateFor(BookType.AUDIOBOOK, audiobookDownloadState),
            readaloudDownloadState = stateFor(BookType.READALOUD, readaloudDownloadState),
        )
    }

}
