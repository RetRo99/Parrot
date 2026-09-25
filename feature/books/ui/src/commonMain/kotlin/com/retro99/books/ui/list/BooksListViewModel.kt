package com.retro99.books.ui.list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.analytics.api.BooksListAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.server.ServerType
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.usecase.BackupAllBooksUseCase
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.domain.usecase.ImportEpubUseCase
import com.retro99.books.domain.usecase.StartBookFileUploadUseCase
import com.retro99.books.domain.usecase.ObserveAllFavoritesUseCase
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.detail.toManualMergeSelections
import com.retro99.books.ui.model.BookFilterState
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookListSettings
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.toUiModel
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.isActiveFor
import com.retro99.library.domain.grouping.LibraryManualGroupingRepository
import com.retro99.library.domain.grouping.MergeLibraryGroupMembersUseCase
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupProjectionRepository
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.usecase.ObserveAllBooksWithProgressUseCase
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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
    @Provided private val groupProjectionRepository: LibraryGroupProjectionRepository,
    @Provided manualGroupingRepository: LibraryManualGroupingRepository,
) : BaseViewModel<BooksListViewState, BooksListIntent>(BooksListViewState()) {

    private var currentBooks: List<BookWithProgressDomainModel> = emptyList()
    private val selectedMergeGroupsById = linkedMapOf<String, LibraryBookGroup>()
    private var mergeSelectionJob: Job? = null
    private val mergeLibraryGroupMembersUseCase =
        MergeLibraryGroupMembersUseCase(manualGroupingRepository)
    val searchFieldState = TextFieldState()

    init {
        observeActiveCloudAccount()
        observeFilterSortSettings()
        observeBooks()
        observeFavorites()
        observeSearchQuery()
    }

    override fun onIntent(intent: BooksListIntent) {
        when (intent) {
            BooksListIntent.OnRefresh -> {
                cancelMergeSelection()
                refreshProgressInfo()
            }
            BooksListIntent.OnSearchToggled -> toggleSearch()
            is BooksListIntent.OnBookClicked -> {
                if (viewState.value.isMergeSelectionMode) {
                    toggleMergeGroupSelection(intent.book.unifiedGroupId)
                } else {
                    onNavigateToBookDetail(intent.book)
                }
            }
            BooksListIntent.OnMergeSelectionStarted -> startMergeSelection()
            BooksListIntent.OnMergeSelectionCancelled -> cancelMergeSelection()
            is BooksListIntent.OnMergeGroupSelectionChanged -> {
                setMergeGroupSelection(intent.groupId, intent.selected)
            }
            BooksListIntent.OnMergeRequested -> requestMerge()
            is BooksListIntent.OnMergeMetadataSourceSelected -> {
                selectMergeMetadataSource(intent.sourceKey)
            }
            BooksListIntent.OnMergeConfirmed -> mergeSelectedGroups()
            BooksListIntent.OnMergeDismissed -> updateState {
                it.copy(showMergeConfirmation = false)
            }
            is BooksListIntent.OnFavoriteClicked -> toggleFavorite(intent.book)
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
            is BooksListIntent.OnServerTypeFilterChanged -> setServerTypeFilter(intent.serverType)
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

    private fun startMergeSelection() {
        mergeSelectionJob?.cancel()
        selectedMergeGroupsById.clear()
        updateState {
            it.copy(
                isMergeSelectionMode = true,
                selectedMergeGroupIds = emptySet(),
                mergeMetadataOptions = emptyList(),
                preferredMergeMetadataSourceKey = null,
                isResolvingMergeSelection = false,
                showMergeConfirmation = false,
                mergeSelectionError = false,
            )
        }
    }

    private fun cancelMergeSelection() {
        if (viewState.value.isMergingGroups) return
        mergeSelectionJob?.cancel()
        mergeSelectionJob = null
        selectedMergeGroupsById.clear()
        updateState {
            it.copy(
                isMergeSelectionMode = false,
                selectedMergeGroupIds = emptySet(),
                mergeMetadataOptions = emptyList(),
                preferredMergeMetadataSourceKey = null,
                isResolvingMergeSelection = false,
                showMergeConfirmation = false,
                mergeSelectionError = false,
            )
        }
    }

    private fun toggleMergeGroupSelection(groupId: String?) {
        if (groupId == null) {
            updateState { it.copy(mergeSelectionError = true) }
            return
        }
        val selected = groupId !in viewState.value.selectedMergeGroupIds
        setMergeGroupSelection(groupId, selected)
    }

    private fun setMergeGroupSelection(groupId: String, selected: Boolean) {
        if (!viewState.value.isMergeSelectionMode || viewState.value.isMergingGroups) return
        if (!selected) {
            selectedMergeGroupsById.remove(groupId)
            val options = selectedMergeMetadataOptions()
            updateState { state ->
                state.copy(
                    selectedMergeGroupIds = state.selectedMergeGroupIds - groupId,
                    mergeMetadataOptions = options,
                    preferredMergeMetadataSourceKey = state.preferredMergeMetadataSourceKey
                        .takeIf { sourceKey ->
                            options.any { option -> option.sourceKey == sourceKey }
                        }
                        ?: options.firstOrNull()?.sourceKey,
                    mergeSelectionError = false,
                )
            }
            return
        }
        if (groupId in selectedMergeGroupsById || viewState.value.isResolvingMergeSelection) return

        updateState { it.copy(isResolvingMergeSelection = true, mergeSelectionError = false) }
        mergeSelectionJob = viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                val requestedGroupId = LibraryGroupId(groupId)
                val group = groupProjectionRepository.getGroup(profileId, requestedGroupId)
                check(group != null && group.groupId == requestedGroupId) {
                    "The selected group changed. Reload the library and try again."
                }
                if (!viewState.value.isMergeSelectionMode) return@launch
                check(group.members.isNotEmpty()) {
                    "The selected group no longer has members. Reload the library and try again."
                }
                selectedMergeGroupsById[groupId] = group
                val options = selectedMergeMetadataOptions()
                updateState { state ->
                    state.copy(
                        selectedMergeGroupIds = state.selectedMergeGroupIds + groupId,
                        mergeMetadataOptions = options,
                        preferredMergeMetadataSourceKey = state.preferredMergeMetadataSourceKey
                            .takeIf { sourceKey ->
                                options.any { option -> option.sourceKey == sourceKey }
                            }
                            ?: options.firstOrNull()?.sourceKey,
                        isResolvingMergeSelection = false,
                        mergeSelectionError = false,
                    )
                }
                mergeSelectionJob = null
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (!viewState.value.isMergeSelectionMode) return@launch
                updateState {
                    it.copy(
                        isResolvingMergeSelection = false,
                        mergeSelectionError = true,
                    )
                }
                mergeSelectionJob = null
            }
        }
    }

    private fun requestMerge() {
        if (!viewState.value.canMergeSelectedGroups) return
        if (selectedMergeMetadataOptions().isEmpty()) {
            updateState { it.copy(mergeSelectionError = true) }
            return
        }
        updateState { it.copy(showMergeConfirmation = true, mergeSelectionError = false) }
    }

    private fun selectMergeMetadataSource(sourceKey: SourceBookKey) {
        if (viewState.value.isMergingGroups) return
        val isSelectedMetadataSource = viewState.value.mergeMetadataOptions.any { option ->
            option.sourceKey == sourceKey
        }
        if (!isSelectedMetadataSource) return
        updateState { it.copy(preferredMergeMetadataSourceKey = sourceKey) }
    }

    private fun mergeSelectedGroups() {
        if (!viewState.value.canMergeSelectedGroups) return
        updateState { it.copy(showMergeConfirmation = false, isMergingGroups = true) }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                val selectedMembers = selectedMergeGroupsById.values
                    .flatMap { group -> group.toManualMergeSelections() }
                val preferredMetadataSourceKey = viewState.value.preferredMergeMetadataSourceKey
                mergeLibraryGroupMembersUseCase(
                    profileId,
                    selectedMembers,
                    preferredMetadataSourceKey,
                )
                selectedMergeGroupsById.clear()
                updateState {
                    it.copy(
                        isMergeSelectionMode = false,
                        selectedMergeGroupIds = emptySet(),
                        mergeMetadataOptions = emptyList(),
                        preferredMergeMetadataSourceKey = null,
                        isMergingGroups = false,
                        mergeSelectionError = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                selectedMergeGroupsById.clear()
                updateState {
                    it.copy(
                        isMergeSelectionMode = false,
                        selectedMergeGroupIds = emptySet(),
                        mergeMetadataOptions = emptyList(),
                        preferredMergeMetadataSourceKey = null,
                        isResolvingMergeSelection = false,
                        isMergingGroups = false,
                        mergeSelectionError = true,
                    )
                }
            }
        }
    }

    private fun selectedMergeMetadataOptions(): List<MergeMetadataSourceOption> =
        selectedMergeGroupsById.values.flatMap { group -> group.toMergeMetadataSourceOptions() }

    private fun toggleSearch() {
        val currentlyVisible = viewState.value.isSearchVisible
        if (!currentlyVisible) {
            analytics.logEvent(NavigationAnalyticsEvent.SearchOpened(source = "books_list"))
        }
        if (currentlyVisible) {
            searchFieldState.edit { delete(0, length) }
        }
        updateState { it.copy(isSearchVisible = !currentlyVisible) }
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

    private fun setServerTypeFilter(serverType: ServerType?) {
        analytics.logEvent(
            BooksListAnalyticsEvent.ServerTypeFilterChanged(serverType = serverType?.name),
        )
        updateState { state ->
            state.copy(filterState = state.filterState.copy(serverTypeFilter = serverType))
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
        analytics.logEvent(BooksListAnalyticsEvent.SortChanged(sortConfig = sortConfig::class.simpleName ?: "unknown"))
        updateState { it.copy(sortConfig = sortConfig) }
        saveFilterSortSettings()
    }

    private fun updateViewMode(viewMode: BookListViewMode) {
        analytics.logEvent(BooksListAnalyticsEvent.ViewModeChanged(viewMode = viewMode::class.simpleName ?: "unknown"))
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

    private fun observeSearchQuery() {
        snapshotFlow { searchFieldState.text.toString() }
            .onEach { query ->
                updateState { it.copy(searchQuery = query) }
            }
            .launchIn(viewModelScope)
    }

    private fun toggleFavorite(book: BookUiModel) {
        val action = favoriteClickAction(book, viewState.value.favoriteBookUuids)
        action.bookUuids.forEach { bookUuid ->
            analytics.logEvent(
                BookAnalyticsEvent.FavoriteToggled(
                    bookUuid = bookUuid,
                    isFavorite = action.isFavorite,
                    source = "list",
                )
            )
        }
        viewModelScope.launch {
            action.bookUuids.forEach { bookUuid ->
                toggleFavoriteUseCase.setFavorite(bookUuid, action.isFavorite)
            }
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
                        val progressInfo = buildMap {
                            booksWithProgress.forEach { bookWithProgress ->
                                val info = bookWithProgress.progressInfo?.toUiModel()
                                    ?: return@forEach
                                bookWithProgress.book.unifiedGroupId?.let { groupId ->
                                    put(groupId, info)
                                }
                                bookWithProgress.book.groupMemberUuids.forEach { bookUuid ->
                                    put(bookUuid, info)
                                }
                            }
                        }

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
                        error.log(analytics, "BooksViewModel: Failed to load books")
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
                .onSuccess { book ->
                    analytics.logEvent(BookAnalyticsEvent.BookImported(bookUuid = book.uuid))
                    viewModelScope.launch {
                        maybeQueueImportedBookBackup(book.uuid)
                    }
                }
                .onFailure { error ->
                    analytics.logEvent(
                        BookAnalyticsEvent.BookImportFailed(
                            errorType = error::class.simpleName ?: "unknown",
                        ),
                    )
                    error.log(analytics, "BooksViewModel: Failed to import book")
                }
            updateState { it.copy(isImporting = false) }
        }
    }

    private suspend fun maybeQueueImportedBookBackup(bookUuid: String) {
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
                        importBackupRightsAttested = false,
                    )
                }
            } else {
                startBookFileUploadUseCase(
                    serverId = PARROT_CLOUD_SERVER_ID,
                    localBookUuid = bookUuid,
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
                        localBookUuid = bookUuid,
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
