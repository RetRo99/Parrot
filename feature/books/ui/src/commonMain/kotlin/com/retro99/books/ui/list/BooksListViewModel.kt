package com.retro99.books.ui.list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.analytics.api.BooksListAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.usecase.BackupAllBooksUseCase
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.domain.usecase.ImportEpubUseCase
import com.retro99.books.domain.usecase.StartBookFileUploadUseCase
import com.retro99.books.domain.usecase.ObserveAllFavoritesUseCase
import com.retro99.books.domain.usecase.ObserveLinkSuggestionsUseCase
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.model.BookFilterState
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookListSettings
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.RecentSearches
import com.retro99.books.ui.model.toUiModel
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.isActiveFor
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.usecase.ObserveAllBooksWithProgressUseCase
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class BooksListViewModel(
    @InjectedParam private val onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    @Provided private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    @Provided private val observeAllFavoritesUseCase: ObserveAllFavoritesUseCase,
    @Provided private val importEpubUseCase: ImportEpubUseCase,
    @Provided private val observeAllBooksWithProgressUseCase: ObserveAllBooksWithProgressUseCase,
    @Provided private val analytics: Analytics,
    @Provided private val observeUserPreferenceUseCase: ObserveUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val bookFileTransferManager: BookFileTransferManager,
    @Provided private val backupAllBooksUseCase: BackupAllBooksUseCase,
    @Provided private val uploadRightsAttestationRepository: UploadRightsAttestationRepository,
    @Provided private val cloudProfileLinkRepository: CloudProfileLinkRepository,
    @Provided private val cloudAccountRepository: CloudAccountRepository,
    @Provided private val startBookFileUploadUseCase: StartBookFileUploadUseCase,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val observeLinkSuggestionsUseCase: ObserveLinkSuggestionsUseCase,
) : BaseViewModel<BooksListViewState, BooksListIntent>(BooksListViewState()) {

    private var currentBooks: List<BookWithProgressDomainModel> = emptyList()
    val searchFieldState = TextFieldState()

    init {
        observeActiveCloudAccount()
        observeFilterSortSettings()
        observeBooks()
        observeFavorites()
        observeRecentSearches()
        observeLinkSuggestions()
    }

    private fun observeLinkSuggestions() {
        observeLinkSuggestionsUseCase()
            .onEach { suggestions ->
                updateState { state -> state.copy(linkSuggestionCount = suggestions.size) }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: BooksListIntent) {
        when (intent) {
            BooksListIntent.OnRefresh -> refreshProgressInfo()
            BooksListIntent.OnSearchActivated -> activateSearch()
            BooksListIntent.OnSearchKeyboardDismissed ->
                saveRecentSearch(viewState.value.searchQuery, onlyWithResults = true)
            BooksListIntent.OnSearchClosed -> closeSearch()
            is BooksListIntent.OnSearchQueryChanged -> updateState {
                it.copy(searchQuery = intent.query)
            }
            is BooksListIntent.OnSearchSubmitted -> saveRecentSearch(intent.query)
            is BooksListIntent.OnRecentSearchSelected -> selectRecentSearch(intent)
            BooksListIntent.OnRecentSearchesCleared -> clearRecentSearches()
            is BooksListIntent.OnBookClicked -> {
                saveRecentSearch(viewState.value.searchQuery)
                onNavigateToBookDetail(intent.book)
            }
            is BooksListIntent.OnFavoriteClicked -> toggleFavorite(intent.bookUuid)
            is BooksListIntent.OnImportBook -> importBook(intent.file)
            BooksListIntent.OnBackupAllClicked -> updateState {
                it.copy(
                    showBackupAllConfirmation = true,
                    backupAllRightsAttested = false,
                    backupAllQueuedCount = null,
                    backupAllFailedCount = null,
                    backupAllError = null,
                )
            }
            is BooksListIntent.OnBackupAllAttestationChanged -> updateState {
                it.copy(backupAllRightsAttested = intent.attested)
            }
            BooksListIntent.OnBackupAllConfirmed -> backUpAllBooks()
            BooksListIntent.OnBackupAllDismissed -> updateState {
                it.copy(showBackupAllConfirmation = false, backupAllRightsAttested = false)
            }
            BooksListIntent.OnBackupAllResultDismissed -> updateState {
                it.copy(
                    backupAllQueuedCount = null,
                    backupAllFailedCount = null,
                    backupAllError = null,
                )
            }
            is BooksListIntent.OnImportBackupAttestationChanged -> updateState {
                it.copy(importBackupRightsAttested = intent.attested)
            }
            BooksListIntent.OnImportBackupConfirmed -> confirmImportedBookBackup()
            BooksListIntent.OnImportBackupDismissed -> dismissImportedBookBackupPrompt()
            is BooksListIntent.OnQuickFilterToggled -> toggleQuickFilter(intent.filter)
            is BooksListIntent.OnHomeFilterChanged -> setHomeFilter(intent.home)
            BooksListIntent.OnClearAllFilters -> clearAllFilters()
            BooksListIntent.OnClearQuickFilters -> clearQuickFilters()
            is BooksListIntent.OnSortChanged -> updateSort(intent.sortConfig)
            is BooksListIntent.OnViewModeChanged -> updateViewMode(intent.viewMode)
        }
    }

    private fun refreshProgressInfo() {
        viewModelScope.launch {
            updateState { it.copy(isRefreshing = true) }
            if (currentBooks.isNotEmpty()) {
                observeAllBooksWithProgressUseCase.fetchRemoteProgress(currentBooks)
            }
            updateState { it.copy(isRefreshing = false) }
        }
    }

    private fun activateSearch() {
        if (viewState.value.isSearchActive) return
        analytics.logEvent(NavigationAnalyticsEvent.SearchOpened(source = "books_list"))
        updateState { it.copy(isSearchActive = true) }
    }

    private fun closeSearch() {
        saveRecentSearch(viewState.value.searchQuery, onlyWithResults = true)
        searchFieldState.edit { delete(0, length) }
        updateState { it.copy(isSearchActive = false, searchQuery = "") }
    }

    private fun selectRecentSearch(intent: BooksListIntent.OnRecentSearchSelected) {
        searchFieldState.setTextAndPlaceCursorAtEnd(intent.query)
        if (intent.run) {
            updateState { it.copy(searchQuery = intent.query) }
            saveRecentSearch(intent.query)
        }
    }

    private fun saveRecentSearch(query: String, onlyWithResults: Boolean = false) {
        if (query.isBlank()) return
        if (onlyWithResults && viewState.value.filteredBooks.isEmpty()) return
        val updated = RecentSearches(viewState.value.recentSearches).with(query)
        updateState { it.copy(recentSearches = updated.queries) }
        saveUserPreferenceUseCase(PreferencesKey.RecentLibrarySearches, updated)
    }

    private fun clearRecentSearches() {
        updateState { it.copy(recentSearches = emptyList()) }
        saveUserPreferenceUseCase(PreferencesKey.RecentLibrarySearches, RecentSearches())
    }

    private fun toggleQuickFilter(filter: BookQuickFilter) {
        val isEnabling = filter !in viewState.value.filterState.activeQuickFilters
        analytics.logEvent(
            BooksListAnalyticsEvent.QuickFilterToggled(
                filter = filter.name,
                isEnabled = isEnabling,
            ),
        )
        updateState { state ->
            val currentFilters = state.filterState.activeQuickFilters
            val newFilters = if (filter in currentFilters) {
                currentFilters - filter
            } else {
                currentFilters + filter
            }
            state.copy(filterState = state.filterState.copy(activeQuickFilters = newFilters))
        }
        saveFilterSortSettings()
    }

    private fun setHomeFilter(home: BookHome?) {
        // The analytics event keeps its name; it now carries where books live.
        analytics.logEvent(
            BooksListAnalyticsEvent.ServerTypeFilterChanged(serverType = home?.name),
        )
        updateState { state ->
            state.copy(filterState = state.filterState.copy(homeFilter = home))
        }
        saveFilterSortSettings()
    }

    private fun clearAllFilters() {
        updateState { it.copy(filterState = BookFilterState()) }
        saveFilterSortSettings()
    }

    private fun clearQuickFilters() {
        updateState { state ->
            state.copy(filterState = state.filterState.copy(activeQuickFilters = emptySet()))
        }
        saveFilterSortSettings()
    }

    private fun updateSort(sortConfig: BookSortConfig) {
        analytics.logEvent(BooksListAnalyticsEvent.SortChanged(sortConfig = sortConfig.toAnalyticsValue()))
        updateState { it.copy(sortConfig = sortConfig) }
        saveFilterSortSettings()
    }

    private fun updateViewMode(viewMode: BookListViewMode) {
        analytics.logEvent(BooksListAnalyticsEvent.ViewModeChanged(viewMode = viewMode.toAnalyticsValue()))
        updateState { it.copy(viewMode = viewMode) }
        saveFilterSortSettings()
    }

    private fun observeFilterSortSettings() {
        observeUserPreferenceUseCase<BookListSettings>(PreferencesKey.BookListFilterSort)
            .onEach { settings ->
                if (settings != null) {
                    updateState {
                        it.copy(
                            filterState = settings.filterState,
                            sortConfig = settings.sortConfig,
                            viewMode = settings.viewMode,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun saveFilterSortSettings() {
        val state = viewState.value
        val settings = BookListSettings(
            filterState = state.filterState,
            sortConfig = state.sortConfig,
            viewMode = state.viewMode,
        )
        saveUserPreferenceUseCase(PreferencesKey.BookListFilterSort, settings)
    }

    private fun observeRecentSearches() {
        observeUserPreferenceUseCase<RecentSearches>(PreferencesKey.RecentLibrarySearches)
            .onEach { recents ->
                updateState { it.copy(recentSearches = recents?.queries.orEmpty()) }
            }
            .launchIn(viewModelScope)
    }

    private fun toggleFavorite(bookUuid: String) {
        val currentIsFavorite = viewState.value.favoriteBookUuids.contains(bookUuid)
        analytics.logEvent(
            BookAnalyticsEvent.FavoriteToggled(
                bookUuid = bookUuid,
                isFavorite = !currentIsFavorite,
                source = "list",
            )
        )
        viewModelScope.launch {
            toggleFavoriteUseCase(bookUuid)
        }
    }

    private fun observeFavorites() {
        observeAllFavoritesUseCase()
            .onEach { favoriteUuids ->
                updateState {
                    it.copy(favoriteBookUuids = favoriteUuids)
                }
            }
            .launchIn(viewModelScope)
    }

    private var hasInitiallyFetchedRemoteProgress = false

    private fun observeBooks() {
        observeAllBooksWithProgressUseCase()
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { result ->
                result
                    .onSuccess { booksWithProgress ->
                        currentBooks = booksWithProgress

                        if (!hasInitiallyFetchedRemoteProgress && booksWithProgress.isNotEmpty()) {
                            hasInitiallyFetchedRemoteProgress = true
                            viewModelScope.launch {
                                observeAllBooksWithProgressUseCase.fetchRemoteProgress(booksWithProgress)
                            }
                        }

                        val uiBooks = booksWithProgress.map { it.book.toUiModel() }
                        val progressInfo = booksWithProgress
                            .filter { it.progressInfo != null }
                            .associate { it.book.uuid to it.progressInfo!!.toUiModel() }

                        updateState {
                            it.copy(
                                books = uiBooks,
                                bookProgressInfo = progressInfo,
                                isLoading = false,
                                isRefreshing = false,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        error.log(
                            analytics,
                            DiagnosticContext(
                                screen = "books_library",
                                action = "load",
                                operation = "load_books",
                                stage = "library_query",
                                outcome = "failed",
                                reasonCode = "books_load_failed",
                            ),
                        )
                        updateState {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                error = error,
                            )
                        }
                    }
            }
            .launchIn(viewModelScope)
    }

    private fun importBook(file: io.github.vinceglb.filekit.core.PlatformFile) {
        viewModelScope.launch {
            updateState { it.copy(isImporting = true) }
            importEpubUseCase(file)
                .onSuccess { imported ->
                    analytics.logEvent(BookAnalyticsEvent.BookImported(bookUuid = imported.libraryBookId))
                    viewModelScope.launch {
                        maybeQueueImportedBookBackup(imported.libraryBookId, imported.mediaType)
                    }
                }
                .onFailure { error ->
                    analytics.logEvent(
                        BookAnalyticsEvent.BookImportFailed(
                            errorType = error::class.simpleName ?: "unknown",
                        ),
                    )
                    error.log(
                        analytics,
                        DiagnosticContext(
                            screen = "books_library",
                            action = "import",
                            operation = "import_book",
                            stage = "import_processing",
                            outcome = "failed",
                            reasonCode = "book_import_failed",
                        ),
                    )
                }
            updateState { it.copy(isImporting = false) }
        }
    }

    private suspend fun maybeQueueImportedBookBackup(bookUuid: String, mediaType: String) {
        if (!bookFileTransferManager.supportsUpload(PARROT_CLOUD_SERVER_ID)) return

        try {
            val localProfileId = userRegistry.getActiveProfileIdOrDefault()
            if (!hasActiveCloudAccount(localProfileId)) return
            val profileLink = cloudProfileLinkRepository.getForLocalProfile(localProfileId)
                ?: return
            if (!profileLink.autoBackupEnabled) return

            if (uploadRightsAttestationRepository.requiresReattestation(localProfileId)) {
                updateState {
                    it.copy(
                        pendingAutoBackupBookUuid = bookUuid,
                        pendingAutoBackupMediaType = mediaType,
                        importBackupRightsAttested = false,
                    )
                }
            } else {
                startBookFileUploadUseCase(
                    serverId = PARROT_CLOUD_SERVER_ID,
                    libraryBookId = bookUuid,
                    mediaType = mediaType,
                    localProfileId = localProfileId,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Import is local-first. An unavailable cloud identity must not fail it.
        }
    }

    private fun confirmImportedBookBackup() {
        val bookUuid = viewState.value.pendingAutoBackupBookUuid ?: return
        val mediaType = viewState.value.pendingAutoBackupMediaType ?: return
        if (!viewState.value.importBackupRightsAttested || viewState.value.isStartingImportBackup) return
        updateState { it.copy(isStartingImportBackup = true) }
        viewModelScope.launch {
            try {
                val localProfileId = userRegistry.getActiveProfileIdOrDefault()
                val profileLink = cloudProfileLinkRepository.getForLocalProfile(localProfileId)
                if (hasActiveCloudAccount(localProfileId) && profileLink?.autoBackupEnabled == true) {
                    uploadRightsAttestationRepository.record(localProfileId)
                    startBookFileUploadUseCase(
                        serverId = PARROT_CLOUD_SERVER_ID,
                        libraryBookId = bookUuid,
                        mediaType = mediaType,
                        localProfileId = localProfileId,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Keep the successful import available locally if backup cannot be queued.
            }
            updateState {
                it.copy(
                    pendingAutoBackupBookUuid = null,
                    pendingAutoBackupMediaType = null,
                    importBackupRightsAttested = false,
                    isStartingImportBackup = false,
                )
            }
        }
    }

    private fun dismissImportedBookBackupPrompt() {
        if (viewState.value.isStartingImportBackup) return
        updateState {
            it.copy(
                pendingAutoBackupBookUuid = null,
                pendingAutoBackupMediaType = null,
                importBackupRightsAttested = false,
            )
        }
    }

    private fun backUpAllBooks() {
        if (!viewState.value.supportsCloudBackup || !viewState.value.backupAllRightsAttested) return
        viewModelScope.launch {
            updateState { it.copy(isBackingUpAll = true, backupAllError = null) }
            try {
                val localProfileId = userRegistry.getActiveProfileIdOrDefault()
                if (!hasActiveCloudAccount(localProfileId)) {
                    updateState {
                        it.copy(
                            showBackupAllConfirmation = false,
                            backupAllRightsAttested = false,
                            isBackingUpAll = false,
                            supportsCloudBackup = false,
                        )
                    }
                    return@launch
                }
                recordUploadAttestationIfRequired(localProfileId)
                val result = backupAllBooksUseCase(
                    serverId = PARROT_CLOUD_SERVER_ID,
                    localProfileId = localProfileId,
                )
                updateState {
                    it.copy(
                        showBackupAllConfirmation = false,
                        backupAllRightsAttested = false,
                        isBackingUpAll = false,
                        backupAllQueuedCount = result.queuedCount,
                        backupAllFailedCount = result.failedCount,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState {
                    it.copy(
                        showBackupAllConfirmation = false,
                        backupAllRightsAttested = false,
                        isBackingUpAll = false,
                        backupAllError = (exception as? BookFileTransferRejectedException)?.reason
                            ?: "backup_all_failed",
                    )
                }
            }
        }
    }

    private suspend fun recordUploadAttestationIfRequired(localProfileId: String) {
        if (uploadRightsAttestationRepository.requiresReattestation(localProfileId)) {
            uploadRightsAttestationRepository.record(localProfileId)
        }
    }

    private fun observeActiveCloudAccount() {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        combine(
            cloudAccountRepository.observeAuthState(),
            cloudProfileLinkRepository.observeForLocalProfile(localProfileId),
        ) { authState, profileLink -> profileLink.isActiveFor(authState) }
            .distinctUntilChanged()
            .onEach { isActive ->
                updateState {
                    it.copy(
                        supportsCloudBackup = isActive &&
                            bookFileTransferManager.supportsUpload(PARROT_CLOUD_SERVER_ID),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun hasActiveCloudAccount(localProfileId: String): Boolean {
        val profileLink = cloudProfileLinkRepository.getForLocalProfile(localProfileId) ?: return false
        return profileLink.isActiveFor(cloudAccountRepository.currentAuthState())
    }
}
