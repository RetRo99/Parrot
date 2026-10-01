package com.retro99.books.ui.detail

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.base.result.log
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.usecase.CancelBookFileTransferUseCase
import com.retro99.books.domain.usecase.DeleteBookFromDeviceUseCase
import com.retro99.books.domain.usecase.RemoveFromParrotCloudUseCase
import com.retro99.books.domain.usecase.ObserveBookFileTransferUseCase
import com.retro99.books.domain.usecase.RetryBookFileTransferUseCase
import com.retro99.books.domain.usecase.StartBookFileUploadUseCase
import com.retro99.books.domain.usecase.StartBookFileDownloadUseCase
import com.retro99.books.domain.usecase.RemoveBookFileDownloadUseCase
import com.retro99.books.domain.usecase.ObserveFavoriteUseCase
import com.retro99.books.domain.usecase.ObserveLinkedCopiesUseCase
import com.retro99.books.domain.usecase.UnlinkCopyUseCase
import com.retro99.books.domain.model.links.CopyKey
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
import com.retro99.reader.domain.usecase.FindLinkedResumeUseCase
import com.retro99.reader.domain.usecase.ResolveLinkedResumeUseCase
import com.retro99.reader.domain.usecase.ResolvePositionConflictUseCase
import com.retro99.server.api.ParrotCloudLibraryState
import com.retro99.server.api.ServerRegistry
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    @InjectedParam private val onNavigateToReader: (
        serverId: String,
        bookUuid: String,
        bookType: BookType,
        bookTitle: String,
        linkedResumeResolved: Boolean,
        listenMode: Boolean,
    ) -> Unit,
    @InjectedParam private val onNavigateToSeriesDetail: (seriesUuid: String, seriesName: String) -> Unit,
    @InjectedParam private val onBack: () -> Unit,
    @InjectedParam private val onNavigateToLinkPicker: (serverId: String, bookUuid: String) -> Unit,
    @InjectedParam private val onNavigateToBookDetail: (serverId: String, bookUuid: String) -> Unit,
    @InjectedParam private val onNavigateToPositions: (serverId: String, bookUuid: String) -> Unit,
    @Provided private val observeBookWithProgressUseCase: ObserveBookWithProgressUseCase,
    @Provided private val downloadMediaUseCase: DownloadMediaUseCase,
    @Provided private val cancelDownloadUseCase: CancelDownloadUseCase,
    @Provided private val observeDownloadStateUseCase: ObserveDownloadStateUseCase,
    @Provided private val deleteMediaCacheUseCase: DeleteMediaCacheUseCase,
    @Provided private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    @Provided private val observeFavoriteUseCase: ObserveFavoriteUseCase,
    @Provided private val resolvePositionConflictUseCase: ResolvePositionConflictUseCase,
    @Provided private val deleteBookFromDeviceUseCase: DeleteBookFromDeviceUseCase,
    @Provided private val removeFromParrotCloudUseCase: RemoveFromParrotCloudUseCase,
    @Provided private val bookFileTransferManager: BookFileTransferManager,
    @Provided private val observeBookFileTransferUseCase: ObserveBookFileTransferUseCase,
    @Provided private val startBookFileUploadUseCase: StartBookFileUploadUseCase,
    @Provided private val cancelBookFileTransferUseCase: CancelBookFileTransferUseCase,
    @Provided private val retryBookFileTransferUseCase: RetryBookFileTransferUseCase,
    @Provided private val startBookFileDownloadUseCase: StartBookFileDownloadUseCase,
    @Provided private val removeBookFileDownloadUseCase: RemoveBookFileDownloadUseCase,
    @Provided private val uploadRightsAttestationRepository: UploadRightsAttestationRepository,
    @Provided private val parrotCloudLibraryState: ParrotCloudLibraryState,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val analytics: Analytics,
    @Provided private val observeLinkedCopiesUseCase: ObserveLinkedCopiesUseCase,
    @Provided private val unlinkCopyUseCase: UnlinkCopyUseCase,
    @Provided private val findLinkedResumeUseCase: FindLinkedResumeUseCase,
    @Provided private val resolveLinkedResumeUseCase: ResolveLinkedResumeUseCase,
    @Provided private val serverRegistry: ServerRegistry,
) : BaseViewModel<BookDetailViewState, BookDetailIntent>(
    BookDetailViewState(),
) {
    private var transferObservationJob: Job? = null
    private var observedTransferKey: String? = null
    private var parrotActive = false
    private var bookObservationJob: Job? = null

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
        observeParrotCloudState()
        observeLinkedCopies()
    }

    override fun onIntent(intent: BookDetailIntent) {
        when (intent) {
            BookDetailIntent.OnReturnedToDetail -> {
                updateState { state -> state.returnFromPositionComparison() }
            }
            is BookDetailIntent.OnListenClicked -> handleReadClick(intent.bookType, listenMode = true)
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

            BookDetailIntent.OnRemoveFromParrotClicked -> {
                updateState { it.copy(showRemoveFromParrotConfirmation = true) }
            }

            BookDetailIntent.OnRemoveFromParrotConfirmed -> removeFromParrotCloud()

            BookDetailIntent.OnRemoveFromParrotDismissed -> {
                updateState { it.copy(showRemoveFromParrotConfirmation = false) }
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

            BookDetailIntent.OnLinkedResumeContinueClicked -> answerLinkedResume(accept = true)

            BookDetailIntent.OnLinkedResumeStayClicked -> answerLinkedResume(accept = false)

            BookDetailIntent.OnLinkedResumeCompareClicked -> {
                // Comparing isn't an answer: nothing is recorded, and the book doesn't open.
                updateState { state ->
                    state.beginPositionComparison()
                }
                onNavigateToPositions(serverId, bookUuid)
            }

            BookDetailIntent.OnReadingPositionsClicked -> onNavigateToPositions(serverId, bookUuid)

            BookDetailIntent.OnSameBookAsClicked -> onNavigateToLinkPicker(serverId, bookUuid)

            is BookDetailIntent.OnOpenLinkedCopyClicked ->
                onNavigateToBookDetail(intent.copy.serverId, intent.copy.uuid)

            is BookDetailIntent.OnNotSameBookClicked -> {
                updateState { state -> state.copy(unlinkConfirmationCopy = intent.copy) }
            }

            BookDetailIntent.OnNotSameBookConfirmed -> unlinkConfirmedCopy()

            BookDetailIntent.OnNotSameBookDismissed -> {
                updateState { state -> state.copy(unlinkConfirmationCopy = null) }
            }
        }
    }

    private fun observeLinkedCopies() {
        combine(
            observeLinkedCopiesUseCase(serverId, bookUuid),
            serverRegistry.observeAllServers(),
        ) { copies, servers ->
            copies.map { copy ->
                val server = servers.firstOrNull { candidate -> candidate.id == copy.serverId }
                copy.toUiModel().copy(serverLabel = server?.baseUrl
                    ?.substringAfter("://")?.substringBefore('/')?.takeIf { host ->
                        host.isNotBlank()
                    })
            }
        }
            .onEach { copies ->
                updateState { state ->
                    state.copy(linkedCopies = copies)
                }
            }
            .launchIn(viewModelScope)
    }

    /** "Not the same book": takes the chosen copy out of this book's link. */
    private fun unlinkConfirmedCopy() {
        val copy = viewState.value.unlinkConfirmationCopy ?: return
        updateState { state -> state.copy(unlinkConfirmationCopy = null) }
        val key = CopyKey.parse(copy.key) ?: return
        viewModelScope.launch {
            unlinkCopyUseCase(key).onFailure { error ->
                error.log(analytics, "BookDetailViewModel: Failed to unlink a copy")
            }
        }
    }

    private fun deleteLocalBook() {
        val book = viewState.value.book as? BookUiModel.LibraryBook ?: return
        if (viewState.value.libraryBookActions?.deleteFromDevice != true) return
        viewModelScope.launch {
            deleteBookFromDeviceUseCase(book.libraryBookId)
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

    private fun handleReadClick(bookType: BookType, listenMode: Boolean = false) {
        updateState { state -> state.copy(pendingListenMode = listenMode) }
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
            viewModelScope.launch {
                // A newer reading in a linked copy is offered instead of the same-copy
                // conflict: it already weighs this copy's local and remote positions.
                val offer = findLinkedResumeUseCase(serverId, bookUuid)
                val state = viewState.value
                val hasConflict = state.progressInfo?.hasConflict == true
                when (bookDetailOpenPrompt(offer, hasConflict)) {
                    BookDetailOpenPrompt.LinkedResume -> updateState { current ->
                        current.copy(linkedResumeOffer = offer, pendingOpenBookType = bookType)
                    }
                    // Check for conflict - show dialog for user to resolve first
                    BookDetailOpenPrompt.SameCopyConflict -> {
                        updateState { current -> current.copy(pendingOpenBookType = bookType) }
                    }
                    BookDetailOpenPrompt.None -> navigateToReader(bookType, state.book?.title ?: "")
                }
            }
        }
        // If not cached, user should click download first
    }

    /** "Continue" or "Stay here"; either way the reader opens without asking again. */
    private fun answerLinkedResume(accept: Boolean) {
        val offer = viewState.value.linkedResumeOffer ?: return
        val bookType = viewState.value.pendingOpenBookType
        updateState { state -> state.copy(linkedResumeOffer = null, pendingOpenBookType = null) }
        viewModelScope.launch {
            if (accept) {
                resolveLinkedResumeUseCase.continueFrom(offer).onFailure { error ->
                    error.log(
                        analytics,
                        "BookDetailViewModel: Failed to continue from another copy",
                    )
                }
            } else {
                resolveLinkedResumeUseCase.stayHere(offer)
            }
            bookType?.let { type ->
                val bookTitle = viewState.value.book?.title ?: ""
                navigateToReader(type, bookTitle, linkedResumeResolved = true)
            }
        }
    }

    /**
     * Observes book data with progress information.
     * Uses database-driven reactivity for reliable updates even when the screen is recreated.
     * This replaces the separate fetchBook() and observeProgressChanges() methods.
     */
    private fun observeBookWithProgress() {
        bookObservationJob?.cancel()
        bookObservationJob = observeBookWithProgressUseCase(serverId, bookUuid)
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
                                progressInfo = bookWithProgress.progressInfo?.toUiModel(),
                                isLoading = false,
                                error = null,
                            ).withLibraryActions().withCloudTransferStates()
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

    private fun observeParrotCloudState() {
        parrotCloudLibraryState.observeIsActive()
            .onEach { isActive ->
                parrotActive = isActive
                updateState { current ->
                    current.copy(parrotActive = isActive).withLibraryActions()
                }
            }
            .launchIn(viewModelScope)
    }

    /** Derives every library-book action from the rules in [LibraryBookActions]. */
    private fun BookDetailViewState.withLibraryActions(): BookDetailViewState {
        val libraryBook = book as? BookUiModel.LibraryBook
            ?: return copy(
                supportsBookBackup = false,
                libraryMediaActions = emptyMap(),
                libraryBookActions = null,
                // Server books keep today's cache delete for any cached type.
                removableDownloadTypes = BookType.entries.toSet(),
            )
        val uploadsSupported = bookFileTransferManager.supportsUpload(PARROT_CLOUD_SERVER_ID)
        val mediaActions = libraryBook.mediaResources.mapNotNull { resource ->
            val bookType = BookType.entries.firstOrNull { type -> type.value == resource.mediaType }
                ?: return@mapNotNull null
            val actions = resource.libraryActions(parrotActive)
            bookType to actions.copy(addToParrot = actions.addToParrot && uploadsSupported)
        }.toMap()
        return copy(
            supportsBookBackup = mediaActions.values.any { actions -> actions.addToParrot },
            libraryMediaActions = mediaActions,
            libraryBookActions = libraryBook.bookActions(),
            removableDownloadTypes = mediaActions
                .filterValues { actions -> actions.removeDownload }
                .keys,
        )
    }

    private fun removeFromParrotCloud() {
        val book = viewState.value.book as? BookUiModel.LibraryBook ?: return
        updateState { it.copy(showRemoveFromParrotConfirmation = false) }
        if (viewState.value.libraryBookActions?.removeFromParrot != true) return
        val parrotMediaTypes = book.mediaResources
            .filter { resource -> resource.cloudBookFileId != null }
            .map { resource -> resource.mediaType }
        viewModelScope.launch {
            try {
                removeFromParrotCloudUseCase(book.libraryBookId, parrotMediaTypes)
            } catch (exception: CancellationException) {
                throw exception
            } catch (error: Exception) {
                updateState {
                    it.copy(bookFileTransferError = error.message ?: "Could not remove from Parrot Cloud")
                }
            }
        }
    }

    private fun observeBookFileTransfers(book: BookUiModel) {
        // Only books in your library have Parrot Cloud files.
        val libraryBookId = (book as? BookUiModel.LibraryBook)?.libraryBookId
        val transferServerId = PARROT_CLOUD_SERVER_ID
        val key = libraryBookId?.let { "$transferServerId:$it" }
        if (key == observedTransferKey) return
        observedTransferKey = key
        transferObservationJob?.cancel()
        if (libraryBookId == null) {
            updateState { it.copy(bookFileTransfers = emptyList()) }
            return
        }
        transferObservationJob = observeBookFileTransferUseCase(transferServerId, libraryBookId)
            .onEach { transfers -> updateState { it.copy(bookFileTransfers = transfers).withCloudTransferStates() } }
            .launchIn(viewModelScope)
    }

    private fun startBookBackup() {
        val book = viewState.value.book as? BookUiModel.LibraryBook ?: return
        if (!viewState.value.backupRightsAttested || !viewState.value.supportsBookBackup) return
        val mediaTypes = viewState.value.libraryMediaActions
            .filterValues { actions -> actions.addToParrot }
            .keys
            .map { bookType -> bookType.value }
        viewModelScope.launch {
            try {
                val localProfileId = userRegistry.getActiveProfileIdOrDefault()
                if (!parrotActive) {
                    updateState { it.copy(showBackupConfirmation = false, backupRightsAttested = false) }
                    return@launch
                }
                recordUploadAttestationIfRequired(localProfileId)
                mediaTypes.forEach { mediaType ->
                    startBookFileUploadUseCase(
                        serverId = PARROT_CLOUD_SERVER_ID,
                        libraryBookId = book.libraryBookId,
                        mediaType = mediaType,
                        localProfileId = localProfileId,
                    )
                }
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
                    serverId = transfer.serverId,
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
                    it.copy(
                        bookFileTransferError = (error as? BookFileTransferRejectedException)?.reason
                            ?: "replace_failed",
                    )
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

        if (book is BookUiModel.LibraryBook) {
            // A library book downloads from Parrot Cloud, never into the reader cache (I6).
            val canDownload = viewState.value.libraryMediaActions[bookType]?.download == true
            if (!canDownload || !bookFileTransferManager.supportsDownload(PARROT_CLOUD_SERVER_ID)) {
                return
            }
            viewModelScope.launch {
                runCatching {
                    startBookFileDownloadUseCase(
                        PARROT_CLOUD_SERVER_ID,
                        book.libraryBookId,
                        bookType.value,
                    )
                }.onFailure { error ->
                    updateState { it.copy(bookFileTransferError = error.message ?: "Could not download book") }
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
        val libraryBook = viewState.value.book as? BookUiModel.LibraryBook
        if (bookType !in viewState.value.removableDownloadTypes) return
        viewModelScope.launch {
            if (libraryBook != null) {
                removeBookFileDownloadUseCase(
                    PARROT_CLOUD_SERVER_ID,
                    libraryBook.libraryBookId,
                    bookType.value,
                )
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

    private fun navigateToReader(
        bookType: BookType,
        bookTitle: String,
        linkedResumeResolved: Boolean = false,
    ) {
        onNavigateToReader(
            serverId, bookUuid, bookType, bookTitle, linkedResumeResolved,
            viewState.value.pendingListenMode,
        )
    }

    /**
     * A library book's media type is on the device when it has a device file, and is
     * downloading while a Parrot Cloud download runs. The reader cache isn't asked (I6).
     */
    private fun BookDetailViewState.withCloudTransferStates(): BookDetailViewState {
        val libraryBook = book as? BookUiModel.LibraryBook ?: return this

        fun stateFor(bookType: BookType): DownloadState {
            if (libraryBook.mediaResource(bookType)?.localPath != null) return DownloadState.Cached
            val transfer = bookFileTransfers.firstOrNull { item ->
                item.direction == "download" &&
                    item.mediaType.equals(bookType.value, ignoreCase = true) &&
                    item.state in ACTIVE_DOWNLOAD_STATES
            } ?: return DownloadState.Idle
            return when (transfer.state) {
                "pending" -> DownloadState.Downloading(0f)
                "transferring" -> DownloadState.Downloading(
                    if (transfer.totalBytes > 0) {
                        (transfer.bytesTransferred.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
                    } else null,
                )
                else -> DownloadState.Downloading(1f)
            }
        }

        return copy(
            ebookDownloadState = stateFor(BookType.EBOOK),
            audiobookDownloadState = stateFor(BookType.AUDIOBOOK),
            readaloudDownloadState = stateFor(BookType.READALOUD),
        )
    }

    private companion object {
        val ACTIVE_DOWNLOAD_STATES = setOf("pending", "transferring", "verifying", "finalizing")
    }
}
