package com.retro99.books.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.CoilImage
import com.retro99.books.ui.components.BookItemCard
import com.retro99.books.ui.components.BookSearchBar
import com.retro99.books.ui.model.BookListItem
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.ImportedBookUiModel
import com.retro99.translations.StringRes
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.books_series_with_position

@Composable
fun BooksListScreen(
    onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    onNavigateToImportedBook: (bookUuid: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BooksViewModel = koinViewModel { parametersOf(onNavigateToBookDetail, onNavigateToImportedBook) },
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
) {
    val coroutineScope = rememberCoroutineScope()
    val filePickerLauncher = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("epub")),
        mode = PickerMode.Single,
    ) { file ->
        file?.let { platformFile ->
            coroutineScope.launch {
                val bytes = platformFile.readBytes()
                intentDispatcher(
                    BooksListIntent.OnFileSelected(
                        fileBytes = bytes,
                        fileName = platformFile.name,
                    )
                )
            }
        }
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Import FAB
                SmallFloatingActionButton(
                    onClick = { filePickerLauncher.launch() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Import EPUB",
                    )
                }

                // Search FAB
                FloatingActionButton(
                    onClick = { intentDispatcher(BooksListIntent.OnSearchToggled) },
                ) {
                    Icon(
                        imageVector = if (viewState.isSearchVisible) {
                            Icons.Filled.Close
                        } else {
                            Icons.Filled.Search
                        },
                        contentDescription = null,
                    )
                }
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

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = viewState.filteredBooks,
                        key = { item ->
                            when (item) {
                                is BookListItem.LocalBook -> "local_${item.uuid}"
                                is BookListItem.StorytellerBook -> "storyteller_${item.uuid}"
                            }
                        },
                    ) { item ->
                        when (item) {
                            is BookListItem.LocalBook -> {
                                ImportedBookItemCard(
                                    book = item.book,
                                    onClick = {
                                        intentDispatcher(BooksListIntent.OnImportedBookClicked(item.uuid))
                                    },
                                )
                            }

                            is BookListItem.StorytellerBook -> {
                                BookItemCard(
                                    book = item.book,
                                    isFavorite = item.isFavorite,
                                    onClick = {
                                        intentDispatcher(BooksListIntent.OnBookClicked(item.book))
                                    },
                                    onFavoriteClick = {
                                        intentDispatcher(BooksListIntent.OnFavoriteClicked(item.uuid))
                                    },
                                    subtitleContent = {
                                        if (item.book.series.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            val seriesInfo = item.book.series.first()
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
}

@Composable
private fun ImportedBookItemCard(
    book: ImportedBookUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Cover image or placeholder
            if (book.coverPath != null) {
                CoilImage(
                    data = "file://${book.coverPath}",
                    cacheKey = book.uuid,
                    modifier = Modifier
                        .size(width = 80.dp, height = 120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    contentDescription = book.title,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 120.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                book.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = "Imported",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
