package com.retro99.books.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.books.ui.components.BookFilterBottomSheet
import com.retro99.books.ui.components.BookGridCard
import com.retro99.books.ui.components.BookItemCard
import com.retro99.books.ui.components.BookSearchBar
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookSortOption
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.SortDirection
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.books_empty_filtered_subtitle
import resources.translations.books_empty_filtered_title
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

@Composable
fun BooksListScreen(
    onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    modifier: Modifier = Modifier,
    headerContent: @Composable (() -> Unit)? = null,
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
    headerContent: @Composable (() -> Unit)? = null,
) {
    val filePickerLauncher = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("epub")),
        mode = PickerMode.Single,
    ) { file ->
        file?.let {
            intentDispatcher(BooksListIntent.OnImportBook(it))
        }
    }

    val listState = rememberLazyListState()
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
                            StringRes.cloud_backup_all_queued,
                            viewState.backupAllQueuedCount ?: 0,
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
            onFilterToggle = { filter ->
                intentDispatcher(BooksListIntent.OnQuickFilterToggled(filter))
            },
            onServerTypeFilterChanged = { serverType ->
                intentDispatcher(BooksListIntent.OnServerTypeFilterChanged(serverType))
            },
            onClearAllFilters = {
                intentDispatcher(BooksListIntent.OnClearAllFilters)
            },
            onClearQuickFilters = {
                intentDispatcher(BooksListIntent.OnClearQuickFilters)
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch() },
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                )
            }
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = viewState.isRefreshing,
            onRefresh = { intentDispatcher(BooksListIntent.OnRefresh) },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                BooksListToolbar(
                    isSearchVisible = viewState.isSearchVisible,
                    onSearchToggled = { intentDispatcher(BooksListIntent.OnSearchToggled) },
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
                    modifier = Modifier.fillMaxWidth(),
                )

                if (viewState.supportsCloudBackup) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { intentDispatcher(BooksListIntent.OnBackupAllClicked) }) {
                        Text(stringResource(StringRes.cloud_backup_backup_all))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = viewState.isSearchVisible,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    BookSearchBar(
                        searchFieldState = searchFieldState,
                        isVisible = viewState.isSearchVisible,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (viewState.filteredBooks.isEmpty() && !viewState.isLoading) {
                    EmptyBooksState(
                        hasActiveFilters = viewState.filterState.hasActiveFilters || viewState.searchQuery.isNotBlank(),
                        onResetFilters = {
                            intentDispatcher(BooksListIntent.OnClearAllFilters)
                            searchFieldState.edit { delete(0, length) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (viewState.viewMode == BookListViewMode.GRID) {
                    BooksGrid(
                        viewState = viewState,
                        intentDispatcher = intentDispatcher,
                        headerContent = headerContent,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (headerContent != null) {
                            item(key = "header") { headerContent() }
                        }
                        items(
                            items = viewState.filteredBooks,
                            key = { it.uuid },
                        ) { book ->
                            BookItemCard(
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
                                subtitleContent = {
                                    if (book.series.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        val seriesInfo = book.series.first()
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
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
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
        }
    }
}

@Composable
private fun BooksListToolbar(
    isSearchVisible: Boolean,
    onSearchToggled: () -> Unit,
    activeFilterCount: Int,
    onFiltersClicked: () -> Unit,
    sortConfig: BookSortConfig,
    onSortChanged: (BookSortConfig) -> Unit,
    viewMode: BookListViewMode,
    onViewModeChanged: (BookListViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSearchToggled) {
                Icon(
                    imageVector = if (isSearchVisible) {
                        Icons.Filled.Clear
                    } else {
                        Icons.Filled.Search
                    },
                    contentDescription = null,
                )
            }

            BadgedBox(
                badge = {
                    if (activeFilterCount > 0) {
                        Badge {
                            Text(text = activeFilterCount.toString())
                        }
                    }
                },
            ) {
                IconButton(onClick = onFiltersClicked) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = null,
                    )
                }
            }

            Spacer(modifier = Modifier.size(4.dp))

            CompactSortSelector(
                sortConfig = sortConfig,
                onSortChanged = onSortChanged,
            )

            Spacer(modifier = Modifier.weight(1f))

            ViewModeIconToggle(
                viewMode = viewMode,
                onViewModeChanged = onViewModeChanged,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSortSelector(
    sortConfig: BookSortConfig,
    onSortChanged: (BookSortConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val option = sortConfig.option
    val directionLabel = if (sortConfig.direction == SortDirection.ASCENDING) {
        option.ascendingLabel
    } else {
        option.descendingLabel
    }

    Box(modifier = modifier) {
        SuggestionChip(
            onClick = { expanded = true },
            label = {
                Text(
                    text = "${stringResource(option.labelRes)} ${stringResource(directionLabel)}",
                )
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BookSortOption.entries.forEach { sortOption ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortOption.labelRes)) },
                    onClick = {
                        if (sortOption == option) {
                            onSortChanged(sortConfig.copy(direction = sortConfig.direction.toggle()))
                        } else {
                            onSortChanged(sortConfig.copy(option = sortOption))
                        }
                        expanded = false
                    },
                )
            }

            HorizontalDivider()

            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (sortConfig.direction == SortDirection.ASCENDING) {
                                Icons.Filled.ArrowUpward
                            } else {
                                Icons.Filled.ArrowDownward
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                if (sortConfig.direction == SortDirection.ASCENDING) {
                                    option.ascendingLabel
                                } else {
                                    option.descendingLabel
                                }
                            ),
                        )
                    }
                },
                onClick = {
                    onSortChanged(sortConfig.copy(direction = sortConfig.direction.toggle()))
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun ViewModeIconToggle(
    viewMode: BookListViewMode,
    onViewModeChanged: (BookListViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onViewModeChanged(BookListViewMode.LIST) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ViewList,
                contentDescription = stringResource(StringRes.books_view_list),
                tint = if (viewMode == BookListViewMode.LIST) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        IconButton(onClick = { onViewModeChanged(BookListViewMode.GRID) }) {
            Icon(
                imageVector = Icons.Filled.GridView,
                contentDescription = stringResource(StringRes.books_view_grid),
                tint = if (viewMode == BookListViewMode.GRID) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun BooksGrid(
    viewState: BooksListViewState,
    intentDispatcher: IntentDispatcher<BooksListIntent>,
    modifier: Modifier = Modifier,
    headerContent: @Composable (() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (headerContent != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                headerContent()
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
                    CircularProgressIndicator()
                }
            }
        }
        gridItems(
            items = viewState.filteredBooks,
            key = { it.uuid },
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
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (hasActiveFilters) {
                        StringRes.books_empty_filtered_title
                    } else {
                        StringRes.books_empty_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (hasActiveFilters) {
                        StringRes.books_empty_filtered_subtitle
                    } else {
                        StringRes.books_empty_subtitle
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            if (hasActiveFilters) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onResetFilters) {
                    Text(text = stringResource(StringRes.books_reset_filters))
                }
            }
        }
    }
}

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

private val BookSortOption.labelRes
    get() = when (this) {
        BookSortOption.TITLE -> StringRes.books_sort_title
        BookSortOption.AUTHOR -> StringRes.books_sort_author
        BookSortOption.RATING -> StringRes.books_sort_rating
        BookSortOption.DATE_PUBLISHED -> StringRes.books_sort_date_published
        BookSortOption.DATE_ADDED -> StringRes.books_sort_date_added
    }

private val BookSortOption.ascendingLabel
    get() = when (this) {
        BookSortOption.TITLE -> StringRes.books_sort_a_to_z
        BookSortOption.AUTHOR -> StringRes.books_sort_a_to_z
        BookSortOption.RATING -> StringRes.books_sort_lowest
        BookSortOption.DATE_PUBLISHED -> StringRes.books_sort_oldest
        BookSortOption.DATE_ADDED -> StringRes.books_sort_oldest
    }

private val BookSortOption.descendingLabel
    get() = when (this) {
        BookSortOption.TITLE -> StringRes.books_sort_z_to_a
        BookSortOption.AUTHOR -> StringRes.books_sort_z_to_a
        BookSortOption.RATING -> StringRes.books_sort_highest
        BookSortOption.DATE_PUBLISHED -> StringRes.books_sort_newest
        BookSortOption.DATE_ADDED -> StringRes.books_sort_newest
    }

private fun SortDirection.toggle(): SortDirection =
    if (this == SortDirection.ASCENDING) {
        SortDirection.DESCENDING
    } else {
        SortDirection.ASCENDING
    }
