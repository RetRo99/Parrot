package com.retro99.books.ui.series

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.ui.compose.ParrotEmptyState
import com.retro99.base.ui.compose.TooltipIconButton
import com.retro99.books.ui.components.BookSearchBar
import com.retro99.books.ui.series.model.SeriesListUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.series_action_search
import resources.translations.series_book_count
import resources.translations.series_book_count_single
import resources.translations.series_empty_subtitle
import resources.translations.series_empty_title
import resources.translations.series_featured
import resources.translations.series_search_placeholder

@Composable
fun SeriesListScreen(
    onNavigateToSeriesDetail: (series: SeriesListUiModel) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SeriesListViewModel = koinViewModel { parametersOf(onNavigateToSeriesDetail) },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        SeriesListScreenContent(
            viewState = viewState,
            intentDispatcher = intentDispatcher,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesListScreenContent(
    viewState: SeriesListViewState,
    intentDispatcher: IntentDispatcher<SeriesListIntent>,
    modifier: Modifier = Modifier,
) {
    val searchFieldState = rememberTextFieldState()
    var isSearchVisible by remember { mutableStateOf(false) }
    val searchQuery = searchFieldState.text.toString().trim()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TooltipIconButton(
                tooltip = stringResource(StringRes.series_action_search),
                icon = if (isSearchVisible) {
                    Icons.Filled.Close
                } else {
                    Icons.Filled.Search
                },
                onClick = {
                    isSearchVisible = !isSearchVisible
                    if (!isSearchVisible) {
                        searchFieldState.edit { delete(0, length) }
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = isSearchVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            BookSearchBar(
                searchFieldState = searchFieldState,
                isVisible = isSearchVisible,
                placeholderRes = StringRes.series_search_placeholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val filteredSeries = if (searchQuery.isBlank()) {
            viewState.series
        } else {
            viewState.series.filter { series ->
                series.name.contains(searchQuery, ignoreCase = true)
            }
        }

        when {
            viewState.isLoading -> LoadingScreen()
            filteredSeries.isEmpty() -> EmptySeriesState(modifier = Modifier.fillMaxSize())
            else -> PullToRefreshBox(
                isRefreshing = viewState.isRefreshing,
                onRefresh = { intentDispatcher(SeriesListIntent.OnRefresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = filteredSeries,
                        key = { _, series -> series.uuid },
                    ) { index, series ->
                        AnimatedSeriesItem(
                            series = series,
                            index = index,
                            onClick = {
                                intentDispatcher(SeriesListIntent.OnSeriesClicked(series))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySeriesState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ParrotEmptyState(
            title = stringResource(StringRes.series_empty_title),
            message = stringResource(StringRes.series_empty_subtitle),
            icon = Icons.Outlined.CollectionsBookmark,
        )
    }
}

@Composable
private fun AnimatedSeriesItem(
    series: SeriesListUiModel,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible.value = true
    }

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = index * 50,
            ),
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = index * 50,
            ),
            initialOffsetY = { it / 4 },
        ),
    ) {
        SeriesItem(
            series = series,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun SeriesItem(
    series: SeriesListUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFeatured = series.featured != null && series.featured > 0

    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Series cover image or fallback icon
            SeriesCover(
                coverUrl = series.coverUrl,
                seriesName = series.name,
                isFeatured = isFeatured,
            )

            // Series info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = series.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Book count
                if (series.bookCount > 0) {
                    Text(
                        text = if (series.bookCount == 1) {
                            stringResource(StringRes.series_book_count_single)
                        } else {
                            stringResource(StringRes.series_book_count, series.bookCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isFeatured) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(StringRes.series_featured),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Chevron indicator
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SeriesCover(
    coverUrl: String?,
    seriesName: String,
    isFeatured: Boolean,
    modifier: Modifier = Modifier,
) {
    if (coverUrl != null) {
        CoilImage(
            data = coverUrl,
            cacheKey = "series_$seriesName",
            modifier = modifier
                .width(56.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            contentDescription = seriesName,
        )
    } else {
        // Fallback icon when no cover is available
        Box(
            modifier = modifier
                .width(56.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isFeatured) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isFeatured) {
                    Icons.Filled.Star
                } else {
                    Icons.Outlined.CollectionsBookmark
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isFeatured) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

