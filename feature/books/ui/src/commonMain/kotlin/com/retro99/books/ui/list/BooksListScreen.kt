package com.retro99.books.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import resources.translations.books_shelf_title
import resources.translations.books_library_title
import com.retro99.base.ui.compose.Ember
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.ParrotEmptyState
import com.retro99.books.ui.components.BookFilterBottomSheet
import com.retro99.books.ui.components.BookGridCard
import com.retro99.books.ui.components.BookItemCard
import com.retro99.books.ui.components.BookSearchBar
import com.retro99.books.ui.components.LibraryDock
import com.retro99.books.ui.components.ShelfHeader
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookSortOption
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.SortDirection
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.books_action_filter
import resources.translations.books_action_sort
import resources.translations.books_action_view
import resources.translations.books_empty_filtered_subtitle
import resources.translations.books_empty_filtered_title
import resources.translations.books_empty_import_cta
import resources.translations.books_empty_subtitle
import resources.translations.books_empty_title
import resources.translations.books_importing
import resources.translations.books_reset_filters
import resources.translations.books_series_with_position
import resources.translations.books_sort_a_to_z
import resources.translations.books_sort_author
import resources.translations.books_sort_date_added
import resources.translations.books_sort_date_published
import resources.translations.books_sort_highest
import resources.translations.books_sort_lowest
import resources.translations.books_sort_newest
import resources.translations.books_sort_oldest
import resources.translations.books_sort_rating
import resources.translations.books_sort_title
import resources.translations.books_sort_z_to_a
import resources.translations.books_view_grid
import resources.translations.books_view_list
import resources.translations.cloud_backup_backup_all
import resources.translations.cloud_backup_all_message
import resources.translations.cloud_backup_all_queued
import resources.translations.cloud_backup_all_summary
import resources.translations.cloud_backup_all_result_title
import resources.translations.cloud_backup_all_title
import resources.translations.cloud_backup_attestation_checkbox
import resources.translations.cloud_backup_autobackup_enable
import resources.translations.cloud_backup_autobackup_not_now
import resources.translations.cloud_backup_autobackup_prompt_body
import resources.translations.cloud_backup_autobackup_prompt_title
import resources.translations.cloud_backup_confirm
import resources.translations.general_cancel
import resources.translations.general_close

/** Bottom padding that keeps the last row clear of the floating dock. */
private val DockContentPadding = 96.dp

private const val SEARCH_DEBOUNCE_MS = 150L
private const val EINK_SEARCH_DEBOUNCE_MS = 500L

@Composable
fun BooksListScreen(
    onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    modifier: Modifier = Modifier,
    headerContent: @Composable ((books: List<BookUiModel>) -> Unit)? = null,
    onSearchActiveChanged: (Boolean) -> Unit = {},
    initialImportRequestId: Long? = null,
    onInitialImportRequestConsumed: (Long) -> Unit = {},
    viewModel: BooksListViewModel = koinViewModel { parametersOf(onNavigateToBookDetail) },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        BooksListScreenContent(
            viewState = viewState,
            searchFieldState = viewModel.searchFieldState,
            intentDispatcher = intentDispatcher,
            modifier = modifier,
            headerContent = headerContent,
            onSearchActiveChanged = onSearchActiveChanged,
            initialImportRequestId = initialImportRequestId,
            onInitialImportRequestConsumed = onInitialImportRequestConsumed,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BooksListScreenContent(
    viewState: BooksListViewState,
    searchFieldState: TextFieldState,
    intentDispatcher: IntentDispatcher<BooksListIntent>,
    modifier: Modifier = Modifier,
    headerContent: @Composable ((books: List<BookUiModel>) -> Unit)? = null,
    onSearchActiveChanged: (Boolean) -> Unit = {},
    initialImportRequestId: Long? = null,
    onInitialImportRequestConsumed: (Long) -> Unit = {},
) {
    val filePickerLauncher = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("epub")),
        mode = PickerMode.Single,
    ) { file ->
        file?.let {
            intentDispatcher(BooksListIntent.OnImportBook(it))
        }
    }

    LaunchedEffect(initialImportRequestId) {
        val requestId = initialImportRequestId ?: return@LaunchedEffect
        filePickerLauncher.launch()
        onInitialImportRequestConsumed(requestId)
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isEink = Ember.style.isEink
    val isSearchActive = viewState.isSearchActive

    val closeSearch: () -> Unit = {
        focusManager.clearFocus()
        intentDispatcher(BooksListIntent.OnSearchClosed)
    }

    LaunchedEffect(isSearchActive) { onSearchActiveChanged(isSearchActive) }
    DisposableEffect(Unit) { onDispose { onSearchActiveChanged(false) } }

    // The keyboard is out of the way once the user scrolls the results.
    LaunchedEffect(searchListState) {
        snapshotFlow { searchListState.isScrollInProgress }
            .filter { scrolling -> scrolling }
            .collect {
                focusManager.clearFocus()
                intentDispatcher(BooksListIntent.OnSearchKeyboardDismissed)
            }
    }

    // Live results; e-ink waits longer between updates to limit screen refreshes.
    val currentSearchQuery by rememberUpdatedState(viewState.searchQuery)
    @OptIn(FlowPreview::class)
    LaunchedEffect(searchFieldState, isEink) {
        val debounceMillis = if (isEink) EINK_SEARCH_DEBOUNCE_MS else SEARCH_DEBOUNCE_MS
        snapshotFlow { searchFieldState.text.toString() }
            .debounce { text -> if (text.isBlank()) 0L else debounceMillis }
            .distinctUntilChanged()
            .collect { text ->
                // Re-collecting after returning from a book must not reset the scroll position.
                if (text != currentSearchQuery) {
                    searchListState.requestScrollToItem(0)
                    intentDispatcher(BooksListIntent.OnSearchQueryChanged(text))
                }
            }
    }

    // Back while the keyboard is showing only hides the keyboard (the system handles that);
    // with it hidden, back closes search.
    if (isSearchActive) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            onBackCompleted = closeSearch,
        )
    }
    var showFilterSheet by remember { mutableStateOf(false) }

    LaunchedEffect(viewState.sortConfig, viewState.filterState.activeQuickFilters) {
        listState.scrollToItem(0)
    }

    if (viewState.isImporting) {
        ImportingDialog()
    }

    if (viewState.showImportBackupAttestation) {
        AlertDialog(
            onDismissRequest = {
                if (!viewState.isStartingImportBackup) {
                    intentDispatcher(BooksListIntent.OnImportBackupDismissed)
                }
            },
            title = { Text(stringResource(StringRes.cloud_backup_autobackup_prompt_title)) },
            text = {
                Column {
                    Text(stringResource(StringRes.cloud_backup_autobackup_prompt_body))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Checkbox(
                            checked = viewState.importBackupRightsAttested,
                            onCheckedChange = {
                                intentDispatcher(BooksListIntent.OnImportBackupAttestationChanged(it))
                            },
                            enabled = !viewState.isStartingImportBackup,
                        )
                        Text(stringResource(StringRes.cloud_backup_attestation_checkbox))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = viewState.importBackupRightsAttested && !viewState.isStartingImportBackup,
                    onClick = { intentDispatcher(BooksListIntent.OnImportBackupConfirmed) },
                ) {
                    if (viewState.isStartingImportBackup) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(StringRes.cloud_backup_autobackup_enable))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !viewState.isStartingImportBackup,
                    onClick = { intentDispatcher(BooksListIntent.OnImportBackupDismissed) },
                ) {
                    Text(stringResource(StringRes.cloud_backup_autobackup_not_now))
                }
            },
        )
    }

    if (viewState.showBackupAllConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!viewState.isBackingUpAll) intentDispatcher(BooksListIntent.OnBackupAllDismissed)
            },
            title = { Text(stringResource(StringRes.cloud_backup_all_title)) },
            text = {
                Column {
                    Text(stringResource(StringRes.cloud_backup_all_message))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Checkbox(
                            checked = viewState.backupAllRightsAttested,
                            onCheckedChange = {
                                intentDispatcher(BooksListIntent.OnBackupAllAttestationChanged(it))
                            },
                        )
                        Text(stringResource(StringRes.cloud_backup_attestation_checkbox))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = viewState.backupAllRightsAttested && !viewState.isBackingUpAll,
                    onClick = { intentDispatcher(BooksListIntent.OnBackupAllConfirmed) },
                ) {
                    if (viewState.isBackingUpAll) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(StringRes.cloud_backup_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !viewState.isBackingUpAll,
                    onClick = { intentDispatcher(BooksListIntent.OnBackupAllDismissed) },
                ) {
                    Text(stringResource(StringRes.general_cancel))
                }
            },
        )
    }

    if (viewState.backupAllQueuedCount != null || viewState.backupAllError != null) {
        AlertDialog(
            onDismissRequest = { intentDispatcher(BooksListIntent.OnBackupAllResultDismissed) },
            title = { Text(stringResource(StringRes.cloud_backup_all_result_title)) },
            text = {
                Text(
                    viewState.backupAllError
                        ?: stringResource(
                            StringRes.cloud_backup_all_summary,
                            viewState.backupAllQueuedCount ?: 0,
                            viewState.backupAllFailedCount ?: 0,
                        ),
                )
            },
            confirmButton = {
                TextButton(onClick = { intentDispatcher(BooksListIntent.OnBackupAllResultDismissed) }) {
                    Text(stringResource(StringRes.general_close))
                }
            },
        )
    }

    if (showFilterSheet) {
        BookFilterBottomSheet(
            filterState = viewState.filterState,
            availableHomes = viewState.availableHomes
                .plus(listOfNotNull(viewState.filterState.homeFilter))
                .distinct(),
            onFilterToggle = { filter ->
                intentDispatcher(BooksListIntent.OnQuickFilterToggled(filter))
            },
            onHomeFilterChanged = { home ->
                intentDispatcher(BooksListIntent.OnHomeFilterChanged(home))
            },
            onClearAllFilters = {
                intentDispatcher(BooksListIntent.OnClearAllFilters)
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    val colors = Ember.colors
    val topContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            LibraryHeader()

            headerContent?.invoke(viewState.books)

            if (viewState.supportsCloudBackup) {
                OutlinedButton(
                    onClick = { intentDispatcher(BooksListIntent.OnBackupAllClicked) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudUpload,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(StringRes.cloud_backup_backup_all))
                }
            }

            ShelfHeader(
                bookCount = viewState.filteredBooks.size,
                activeFilterCount = viewState.filterState.activeFilterCount,
                onFiltersClicked = { showFilterSheet = true },
                sortConfig = viewState.sortConfig,
                onSortChanged = { sortConfig ->
                    intentDispatcher(BooksListIntent.OnSortChanged(sortConfig))
                },
                viewMode = viewState.viewMode,
                onViewModeChanged = { viewMode ->
                    intentDispatcher(BooksListIntent.OnViewModeChanged(viewMode))
                },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = colors.bg,
        // Bottom insets belong to the dock, which rides above the keyboard while searching.
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            PullToRefreshBox(
                isRefreshing = viewState.isRefreshing,
                onRefresh = { intentDispatcher(BooksListIntent.OnRefresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isSearchActive) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LibraryHeader()
                        LibrarySearchContent(
                            viewState = viewState,
                            listState = searchListState,
                            intentDispatcher = intentDispatcher,
                            onRecentRun = { recent ->
                                focusManager.clearFocus()
                                intentDispatcher(
                                    BooksListIntent.OnRecentSearchSelected(recent, run = true),
                                )
                            },
                            onClearSearch = {
                                searchFieldState.edit { delete(0, length) }
                                searchFocusRequester.requestFocus()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (viewState.filteredBooks.isEmpty() && !viewState.isLoading) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        topContent()
                        EmptyBooksState(
                            hasActiveFilters = viewState.filterState.hasActiveFilters,
                            onImportBook = { filePickerLauncher.launch() },
                            onResetFilters = {
                                intentDispatcher(BooksListIntent.OnClearAllFilters)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (viewState.viewMode == BookListViewMode.GRID) {
                    BooksGrid(
                        viewState = viewState,
                        intentDispatcher = intentDispatcher,
                        gridState = gridState,
                        topContent = topContent,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = DockContentPadding),
                    ) {
                        item(key = "top") { topContent() }
                        itemsIndexed(
                            items = viewState.filteredBooks,
                            key = { _, book -> book.uuid },
                        ) { index, book ->
                            BookItemCard(
                                modifier = Modifier.animateItem(),
                                book = book,
                                isFavorite = book.uuid in viewState.favoriteBookUuids,
                                showDivider = index > 0,
                                onClick = {
                                    intentDispatcher(BooksListIntent.OnBookClicked(book))
                                },
                                onFavoriteClick = {
                                    intentDispatcher(BooksListIntent.OnFavoriteClicked(book.uuid))
                                },
                                progressInfo = viewState.bookProgressInfo[book.uuid],
                                showServerBadge = viewState.showServerBadge,
                                subtitleContent = {
                                    val seriesInfo = book.series.firstOrNull()
                                    if (seriesInfo != null) {
                                        val seriesText = if (seriesInfo.position != null) {
                                            stringResource(
                                                StringRes.books_series_with_position,
                                                seriesInfo.name,
                                                seriesInfo.position,
                                            )
                                        } else {
                                            seriesInfo.name
                                        }
                                        Text(
                                            text = seriesText,
                                            style = Ember.type.meta,
                                            color = colors.accentText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            LibraryDock(
                searchFieldState = searchFieldState,
                isSearchActive = isSearchActive,
                focusRequester = searchFocusRequester,
                onSearchFocused = { intentDispatcher(BooksListIntent.OnSearchActivated) },
                onSearchSubmitted = {
                    intentDispatcher(
                        BooksListIntent.OnSearchSubmitted(searchFieldState.text.toString()),
                    )
                    focusManager.clearFocus()
                },
                onCloseSearch = closeSearch,
                onAddClick = { filePickerLauncher.launch() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Screen title. Search and import live in the floating [LibraryDock]. */
@Composable
private fun LibraryHeader(
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(StringRes.books_library_title),
        style = Ember.type.screenTitle,
        color = Ember.colors.ink,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp),
    )
}

@Composable
private fun BooksGrid(
    viewState: BooksListViewState,
    intentDispatcher: IntentDispatcher<BooksListIntent>,
    gridState: LazyGridState,
    topContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 100.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = DockContentPadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "top") {
            // The header sits inside the grid's horizontal padding, so it is pulled back out.
            Box(modifier = Modifier.layout { measurable, constraints ->
                val extra = 40.dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = constraints.maxWidth + extra,
                        maxWidth = constraints.maxWidth + extra,
                    ),
                )
                layout(constraints.maxWidth, placeable.height) {
                    placeable.place(-extra / 2, 0)
                }
            }) {
                topContent()
            }
        }
        if (viewState.isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Ember.colors.accent)
                }
            }
        }
        gridItems(
            items = viewState.filteredBooks,
            key = { book -> book.uuid },
        ) { book ->
            BookGridCard(
                modifier = Modifier.animateItem(),
                book = book,
                isFavorite = book.uuid in viewState.favoriteBookUuids,
                onClick = {
                    intentDispatcher(BooksListIntent.OnBookClicked(book))
                },
                onFavoriteClick = {
                    intentDispatcher(BooksListIntent.OnFavoriteClicked(book.uuid))
                },
                progressInfo = viewState.bookProgressInfo[book.uuid],
                showServerBadge = viewState.showServerBadge,
            )
        }
    }
}

@Composable
private fun EmptyBooksState(
    hasActiveFilters: Boolean,
    onImportBook: () -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (hasActiveFilters) {
            ParrotEmptyState(
                title = stringResource(StringRes.books_empty_filtered_title),
                message = stringResource(StringRes.books_empty_filtered_subtitle),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                actionLabel = stringResource(StringRes.books_reset_filters),
                onAction = onResetFilters,
            )
        } else {
            ParrotEmptyState(
                title = stringResource(StringRes.books_empty_title),
                message = stringResource(StringRes.books_empty_subtitle),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                actionLabel = stringResource(StringRes.books_empty_import_cta),
                onAction = onImportBook,
            )
        }
    }
}

internal fun shouldShowHeaderInEmptyBooksState(
    filteredBooksEmpty: Boolean,
    isLoading: Boolean,
    hasHeaderContent: Boolean,
): Boolean = filteredBooksEmpty && !isLoading && hasHeaderContent

@Composable
private fun ImportingDialog(
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { },
        modifier = modifier,
        confirmButton = { },
        title = {
            Text(
                text = stringResource(StringRes.books_importing),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
    )
}
