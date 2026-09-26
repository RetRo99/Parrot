package com.retro99.books.ui.series.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.TooltipIconButton
import com.retro99.books.ui.components.BookItemCard
import com.retro99.books.ui.components.BookSearchBar
import com.retro99.books.ui.model.BookUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.series_action_search
import resources.translations.series_detail_position

@Composable
fun SeriesDetailScreen(
    seriesUuid: String,
    seriesName: String,
    onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesDetailViewModel = koinViewModel {
        parametersOf(seriesUuid, seriesName, onNavigateToBookDetail, onBack)
    },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        when {
            viewState.isLoading -> LoadingScreen()
            else -> SeriesDetailScreenContent(
                viewState = viewState,
                searchFieldState = viewModel.searchFieldState,
                intentDispatcher = intentDispatcher,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesDetailScreenContent(
    viewState: SeriesDetailViewState,
    searchFieldState: TextFieldState,
    intentDispatcher: IntentDispatcher<SeriesDetailIntent>,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = viewState.seriesName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { intentDispatcher(SeriesDetailIntent.OnBackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(StringRes.general_back),
                        )
                    }
                },
                actions = {
                    TooltipIconButton(
                        tooltip = stringResource(StringRes.series_action_search),
                        icon = if (viewState.isSearchVisible) {
                            Icons.Filled.Close
                        } else {
                            Icons.Filled.Search
                        },
                        onClick = { intentDispatcher(SeriesDetailIntent.OnSearchToggled) },
                    )
                },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = viewState.isRefreshing,
            onRefresh = { intentDispatcher(SeriesDetailIntent.OnRefresh) },
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
                        key = { it.uuid },
                    ) { book ->
                        // Match by name since series UUID from /api/v2/series doesn't match
                        // the numeric ID in books' series data
                        val position = book.series.find {
                            it.name.equals(viewState.seriesName, ignoreCase = true)
                        }?.position

                        BookItemCard(
                            book = book,
                            isFavorite = book.uuid in viewState.favoriteBookUuids,
                            onClick = {
                                intentDispatcher(SeriesDetailIntent.OnBookClicked(book))
                            },
                            onFavoriteClick = {
                                intentDispatcher(SeriesDetailIntent.OnFavoriteClicked(book.uuid))
                            },
                            headerContent = {
                                position?.let {
                                    Text(
                                        text = stringResource(StringRes.series_detail_position, it),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

