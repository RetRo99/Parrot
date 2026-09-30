package com.retro99.books.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.books.ui.components.BookItemCard
import com.retro99.books.ui.components.directionLabel
import com.retro99.books.ui.components.highlightedText
import com.retro99.books.ui.components.labelRes
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_search_clear
import resources.translations.books_search_hint
import resources.translations.books_search_match_count
import resources.translations.books_search_match_count_one
import resources.translations.books_search_none_body
import resources.translations.books_search_none_title
import resources.translations.books_search_recent_clear
import resources.translations.books_search_recent_fill
import resources.translations.books_search_recent_item
import resources.translations.books_search_recent_title
import resources.translations.books_series_with_position

/** Bottom padding that keeps the last result clear of the floating dock. */
private val ResultsBottomPadding = 96.dp

/** Everything the Library shows below its title while search is active. */
@Composable
internal fun LibrarySearchContent(
    viewState: BooksListViewState,
    listState: LazyListState,
    intentDispatcher: IntentDispatcher<BooksListIntent>,
    onRecentRun: (String) -> Unit,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = viewState.searchQuery.trim()
    val results = viewState.filteredBooks

    when {
        query.isEmpty() -> RecentSearches(
            recents = viewState.recentSearches,
            onRun = onRecentRun,
            onFill = { recent ->
                intentDispatcher(BooksListIntent.OnRecentSearchSelected(recent, run = false))
            },
            onClear = { intentDispatcher(BooksListIntent.OnRecentSearchesCleared) },
            modifier = modifier,
        )

        results.isEmpty() -> NoResults(
            query = query,
            onClearSearch = onClearSearch,
            modifier = modifier,
        )

        else -> SearchResults(
            viewState = viewState,
            query = query,
            listState = listState,
            intentDispatcher = intentDispatcher,
            modifier = modifier,
        )
    }
}

@Composable
private fun RecentSearches(
    recents: List<String>,
    onRun: (String) -> Unit,
    onFill: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val type = Ember.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 16.dp),
    ) {
        if (recents.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(StringRes.books_search_recent_title),
                    style = type.eyebrow,
                    color = colors.ink2,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(StringRes.books_search_recent_clear),
                    style = type.label,
                    color = colors.accentText,
                    modifier = Modifier
                        .clickable(onClick = onClear)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            recents.forEach { recent ->
                RecentRow(recent = recent, onRun = onRun, onFill = onFill)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = stringResource(StringRes.books_search_hint),
            style = type.meta,
            color = colors.ink2,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RecentRow(
    recent: String,
    onRun: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    val colors = Ember.colors
    val rowDescription = stringResource(StringRes.books_search_recent_item, recent)
    val fillDescription = stringResource(StringRes.books_search_recent_fill, recent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRun(recent) }
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }
            .padding(start = 24.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.ink2,
        )
        Text(
            text = recent,
            style = Ember.type.author,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onFill(recent) }) {
            Icon(
                imageVector = Icons.Outlined.NorthWest,
                contentDescription = fillDescription,
                modifier = Modifier.size(20.dp),
                tint = colors.ink2,
            )
        }
    }
}

@Composable
private fun SearchResults(
    viewState: BooksListViewState,
    query: String,
    listState: LazyListState,
    intentDispatcher: IntentDispatcher<BooksListIntent>,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val results = viewState.filteredBooks
    val sortLabel = stringResource(viewState.sortConfig.option.labelRes) + " · " +
        stringResource(viewState.sortConfig.option.directionLabel(viewState.sortConfig.direction))
    val countText = if (results.size == 1) {
        stringResource(StringRes.books_search_match_count_one, query)
    } else {
        stringResource(StringRes.books_search_match_count, results.size, query)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = countText,
                style = Ember.type.meta,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = sortLabel,
                style = Ember.type.meta.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink2,
                maxLines = 1,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ResultsBottomPadding),
        ) {
            itemsIndexed(
                items = results,
                key = { _, book -> book.uuid },
            ) { index, book ->
                BookItemCard(
                    book = book,
                    isFavorite = book.uuid in viewState.favoriteBookUuids,
                    showDivider = index > 0,
                    highlightQuery = query,
                    onClick = { intentDispatcher(BooksListIntent.OnBookClicked(book)) },
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
                                text = highlightedText(seriesText, query),
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

@Composable
private fun NoResults(
    query: String,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val title = stringResource(StringRes.books_search_none_title, query)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = colors.ink2,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = Ember.type.cardTitle,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(StringRes.books_search_none_body),
            style = Ember.type.meta,
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(onClick = onClearSearch) {
            Text(stringResource(StringRes.books_search_clear))
        }
    }
}
