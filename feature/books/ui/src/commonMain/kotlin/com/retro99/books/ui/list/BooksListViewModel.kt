package com.retro99.books.ui.list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.FeatureUsageAnalyticsEvent
import com.retro99.analytics.api.DiscoveryRoute
import com.retro99.analytics.api.DiscoveryDestination
import com.retro99.analytics.api.UsageOperation
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.logFeatureUsage
import com.retro99.analytics.api.trackUsageOperation
import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.BackupErrorCategory
import com.retro99.analytics.api.SearchScope
import com.retro99.analytics.api.FeatureExposureTracker
import com.retro99.analytics.api.UsageFeature
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.analytics.api.BooksListAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.BookFileTransferManager
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
import com.retro99.books.ui.model.CloudBackupBook
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.cloudBackupBooks
import com.retro99.books.ui.model.cloudBackupUploadBookIds
import com.retro99.books.ui.model.RecentSearches
import com.retro99.books.ui.model.toUiModel
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.isActiveFor
import com.retro99.cloudaccount.domain.usecase.GetCloudStorageUsageUseCase
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
import kotlinx.coroutines.Job
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import kotlin.time.TimeSource

private val ACTIVE_UPLOAD_STATES = setOf("pending", "transferring", "verifying", "finalizing")

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
    @Provided private val getCloudStorageUsageUseCase: GetCloudStorageUsageUseCase,
    @Provided private val uploadRightsAttestationRepository: UploadRightsAttestationRepository,
    @Provided private val cloudProfileLinkRepository: CloudProfileLinkRepository,
    @Provided private val cloudAccountRepository: CloudAccountRepository,
    @Provided private val startBookFileUploadUseCase: StartBookFileUploadUseCase,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val observeLinkSuggestionsUseCase: ObserveLinkSuggestionsUseCase,
) : BaseViewModel<BooksListViewState, BooksListIntent>(BooksListViewState()) {

    private var currentBooks: List<BookWithProgressDomainModel> = emptyList()
    private var backupTransfersJob: Job? = null
    val searchFieldState = TextFieldState()
    private val featureExposure = FeatureExposureTracker(analytics, "books_library")
    private var lastSearchResults: Triple<String, BookFilterState, Int>? = null

    init {
        observeActiveCloudAccount()
        observeFilterSortSettings()
        observeBooks()
        observeFavorites()
        observeRecentSearches()
        observeLinkSuggestions()
        observeCloudBackupNoteDismissed()
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
            BooksListIntent.OnLinkSuggestionsVisible -> featureExposure.expose(UsageFeature.LinkedCopies, true)
            BooksListIntent.OnScreenVisible -> {
                featureExposure.reset()
                lastSearchResults = null
                featureExposure.expose(UsageFeature.LocalImport, true)
                if (viewState.value.supportsCloudBackup) featureExposure.expose(UsageFeature.Backup, true)
            }
            BooksListIntent.OnSearchResultsVisible -> reportSearchResults()
            BooksListIntent.OnBackupFeatureVisible -> featureExposure.expose(UsageFeature.Backup, true)
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
                val state = viewState.value
                if (state.searchQuery.isNotBlank()) {
                    reportSearchResults()
                    val index = state.filteredBooks.indexOfFirst { it.uuid == intent.book.uuid }
                    if (index >= 0) analytics.logEvent(ProductAnalyticsEvent.SearchResultSelected(SearchScope.Library, index))
                }
                saveRecentSearch(viewState.value.searchQuery)
                val route = when {
                    state.searchQuery.isNotBlank() -> DiscoveryRoute.Search
                    BookQuickFilter.FAVORITES in state.filterState.activeQuickFilters -> DiscoveryRoute.Favorites
                    state.filterState.activeQuickFilters.isNotEmpty() || state.filterState.homeFilter != null -> DiscoveryRoute.Filtered
                    else -> DiscoveryRoute.Library
                }
                analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.DiscoverySelected(route, DiscoveryDestination.Book))
                onNavigateToBookDetail(intent.book)
            }
            is BooksListIntent.OnFavoriteClicked -> toggleFavorite(intent.bookUuid)
            is BooksListIntent.OnImportBook -> importBook(intent.file, intent.openAfterImport)
            BooksListIntent.OnImportedBookOpened -> updateState { it.copy(importedBookToOpen = null) }
            BooksListIntent.OnCloudBackupClicked -> openCloudBackupSelection()
            BooksListIntent.OnCloudBackupNoteDismissed -> dismissCloudBackupNote()
            is BooksListIntent.OnCloudBackupBookToggled -> updateState { state ->
                state.copy(
                    selectedCloudBackupBookIds = if (intent.selected) {
                        state.selectedCloudBackupBookIds + intent.bookId
                    } else {
                        state.selectedCloudBackupBookIds - intent.bookId
                    },
                )
            }
            BooksListIntent.OnCloudBackupSelectAll -> updateState { state ->
                state.copy(selectedCloudBackupBookIds = state.cloudBackupBooks.mapTo(mutableSetOf()) { it.id })
            }
            BooksListIntent.OnCloudBackupSelectNone -> updateState {
                it.copy(selectedCloudBackupBookIds = emptySet())
            }
            is BooksListIntent.OnCloudBackupAttestationChanged -> updateState {
                it.copy(cloudBackupRightsAttested = intent.attested)
            }
            BooksListIntent.OnCloudBackupConfirmed -> addSelectedBooksToCloud()
            BooksListIntent.OnCloudBackupDismissed -> closeCloudBackupSelection()
            BooksListIntent.OnCloudBackupSnackbarDismissed -> updateState {
                it.copy(cloudBackupSnackbarBookCount = null)
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
        lastSearchResults = null
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

    private fun reportSearchResults() {
        val state = viewState.value
        if (state.isLoading || state.error != null || state.searchQuery.isBlank()) return
        val started = TimeSource.Monotonic.markNow()
        val count = state.filteredBooks.size
        val signature = Triple(state.searchQuery, state.filterState, count)
        if (signature == lastSearchResults) return
        lastSearchResults = signature
        analytics.logEvent(ProductAnalyticsEvent.SearchResultsShown(
            scope = SearchScope.Library,
            count = count,
            durationMs = started.elapsedNow().inWholeMilliseconds,
            hasFilters = state.filterState.activeQuickFilters.isNotEmpty() || state.filterState.homeFilter != null,
        ))
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

    private fun observeCloudBackupNoteDismissed() {
        observeUserPreferenceUseCase<Boolean>(PreferencesKey.CloudBackupNoteDismissed)
            .onEach { dismissed ->
                updateState { it.copy(cloudBackupNoteDismissed = dismissed == true) }
            }
            .launchIn(viewModelScope)
    }

    /** Hidden for good; the action itself stays available on the Parrot Cloud screen. */
    private fun dismissCloudBackupNote() {
        updateState { it.copy(cloudBackupNoteDismissed = true) }
        saveUserPreferenceUseCase(PreferencesKey.CloudBackupNoteDismissed, true)
    }

    private var observedBackupTransferBookIds: Set<String> = emptySet()

    private fun observeBackupTransfers(books: List<BookUiModel>) {
        val bookIds = books.cloudBackupUploadBookIds()
        if (bookIds == observedBackupTransferBookIds) return
        observedBackupTransferBookIds = bookIds
        backupTransfersJob?.cancel()
        if (bookIds.isEmpty()) {
            updateState { it.copy(uploadingBookIds = emptySet(), activeCloudUploads = emptyMap()) }
            return
        }
        val transferFlows = bookIds.map { bookId ->
            bookFileTransferManager.observeForBook(PARROT_CLOUD_SERVER_ID, bookId)
        }
        backupTransfersJob = combine(transferFlows) { transferLists ->
            bookIds.zip(transferLists.asList())
                .mapNotNull { (bookId, transfers) ->
                    val activeUploads = transfers.filter { transfer ->
                        transfer.direction == "upload" && transfer.state in ACTIVE_UPLOAD_STATES
                    }
                    bookId.takeIf { activeUploads.isNotEmpty() }?.let { it to activeUploads }
                }
                .toMap()
        }
            .distinctUntilChanged()
            .onEach { activeUploads ->
                updateState {
                    it.copy(
                        uploadingBookIds = activeUploads.keys,
                        activeCloudUploads = activeUploads,
                    )
                }
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
        val started = TimeSource.Monotonic.markNow()
        var initialReported = false
        observeAllBooksWithProgressUseCase()
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { result ->
                result
                    .onSuccess { booksWithProgress ->
                        if (!initialReported) {
                            initialReported = true
                            analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.LibraryLoadCompleted(
                                ProductOutcome.Succeeded, started.elapsedNow().inWholeMilliseconds, booksWithProgress.size,
                            ))
                        }
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
                        observeBackupTransfers(uiBooks)
                    }
                    .onFailure { error ->
                        if (!initialReported) {
                            initialReported = true
                            analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.LibraryLoadCompleted(
                                ProductOutcome.Failed, started.elapsedNow().inWholeMilliseconds, 0,
                            ))
                        }
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

    private fun importBook(file: io.github.vinceglb.filekit.core.PlatformFile, openAfterImport: Boolean) {
        viewModelScope.launch {
            updateState { it.copy(isImporting = true) }
            analytics.trackUsageOperation(
                UsageOperation.Import, UsageAction.Import, "books_library",
                outcome = { if (it.isOk) ProductOutcome.Succeeded else ProductOutcome.Failed },
            ) { importEpubUseCase(file) }
                .onSuccess { imported ->
                    analytics.logEvent(BookAnalyticsEvent.BookImported(bookUuid = imported.libraryBookId))
                    if (openAfterImport) {
                        updateState {
                            it.copy(isImporting = false, importedBookToOpen = imported.libraryBookId)
                        }
                    }
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

    private fun openCloudBackupSelection() {
        val candidates = viewState.value.cloudBackupBooks
        updateState {
            it.copy(
                showCloudBackupSelection = true,
                selectedCloudBackupBookIds = candidates.mapTo(linkedSetOf(), CloudBackupBook::id),
                cloudBackupRightsAttested = false,
                isAddingBooksToCloud = false,
                cloudStorageAvailableBytes = null,
                isLoadingCloudStorage = true,
                cloudStorageUnavailable = false,
            )
        }
        viewModelScope.launch {
            try {
                val usage = getCloudStorageUsageUseCase()
                updateState {
                    it.copy(
                        cloudStorageAvailableBytes = usage.availableBytes,
                        isLoadingCloudStorage = false,
                        cloudStorageUnavailable = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                updateState { it.copy(isLoadingCloudStorage = false, cloudStorageUnavailable = true) }
            }
        }
    }

    private fun closeCloudBackupSelection() {
        if (viewState.value.isAddingBooksToCloud) return
        updateState {
            it.copy(
                showCloudBackupSelection = false,
                selectedCloudBackupBookIds = emptySet(),
                cloudBackupRightsAttested = false,
            )
        }
    }

    private fun addSelectedBooksToCloud() {
        val state = viewState.value
        val selectedBooks = state.selectedCloudBackupBooks
        if (!state.supportsCloudBackup || !state.cloudBackupRightsAttested || selectedBooks.isEmpty() ||
            state.cloudBackupOverQuota || state.isLoadingCloudStorage || state.isAddingBooksToCloud
        ) return
        updateState { it.copy(isAddingBooksToCloud = true) }
        viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            analytics.logEvent(ProductAnalyticsEvent.BookBackupOperation(true, "queue", ProductOutcome.Started, 0L))
            val localProfileId = userRegistry.getActiveProfileIdOrDefault()
            try {
                if (!hasActiveCloudAccount(localProfileId)) {
                    analytics.logEvent(ProductAnalyticsEvent.BookBackupOperation(
                        true, "queue", ProductOutcome.Failed, started.elapsedNow().inWholeMilliseconds,
                        errorCategory = BackupErrorCategory.AuthenticationUnavailable,
                    ))
                    updateState {
                        it.copy(
                            showCloudBackupSelection = false,
                            cloudBackupRightsAttested = false,
                            isAddingBooksToCloud = false,
                            supportsCloudBackup = false,
                        )
                    }
                    return@launch
                }
                recordUploadAttestationIfRequired(localProfileId)
                updateState {
                    it.copy(
                        showCloudBackupSelection = false,
                        selectedCloudBackupBookIds = emptySet(),
                        cloudBackupRightsAttested = false,
                        isAddingBooksToCloud = false,
                        cloudBackupSnackbarBookCount = selectedBooks.size,
                    )
                }

                var queuedFiles = 0
                var failedFiles = 0
                selectedBooks.forEach { candidate ->
                    candidate.mediaTypes.forEach { mediaType ->
                        try {
                            startBookFileUploadUseCase(
                                serverId = PARROT_CLOUD_SERVER_ID,
                                libraryBookId = candidate.id,
                                mediaType = mediaType,
                                localProfileId = localProfileId,
                            )
                            queuedFiles++
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            failedFiles++
                        }
                    }
                }
                analytics.logEvent(ProductAnalyticsEvent.BookBackupOperation(
                    bulk = true,
                    stage = "queue",
                    outcome = when {
                        failedFiles == 0 -> ProductOutcome.Queued
                        queuedFiles > 0 -> ProductOutcome.Partial
                        else -> ProductOutcome.Failed
                    },
                    durationMs = started.elapsedNow().inWholeMilliseconds,
                    queuedCount = queuedFiles,
                    failedCount = failedFiles,
                    errorCategory = BackupErrorCategory.QueueFailed.takeIf { failedFiles > 0 },
                ))
            } catch (exception: CancellationException) {
                analytics.logEvent(ProductAnalyticsEvent.BookBackupOperation(
                    true, "queue", ProductOutcome.Cancelled, started.elapsedNow().inWholeMilliseconds,
                ))
                throw exception
            } catch (_: Exception) {
                analytics.logEvent(ProductAnalyticsEvent.BookBackupOperation(
                    true, "queue", ProductOutcome.Failed, started.elapsedNow().inWholeMilliseconds,
                    errorCategory = BackupErrorCategory.QueueFailed,
                ))
                updateState { it.copy(isAddingBooksToCloud = false) }
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
