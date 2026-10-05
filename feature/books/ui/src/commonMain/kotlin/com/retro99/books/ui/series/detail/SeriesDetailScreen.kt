package com.retro99.books.ui.series.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.*
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.series.SeriesCover
import com.retro99.books.ui.series.SeriesSearch
import com.retro99.books.ui.series.SeriesSourceError
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SeriesDetailScreen(
    seriesUuid: String,
    seriesName: String,
    onNavigateToBookDetail: (BookUiModel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesDetailViewModel = koinViewModel { parametersOf(seriesUuid, seriesName, onNavigateToBookDetail, onBack) },
) {
    BaseScreen(modifier = modifier, viewModel = viewModel) { state, dispatch -> SeriesDetailScreenContent(state, dispatch) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreenContent(viewState: SeriesDetailViewState, intentDispatcher: IntentDispatcher<SeriesDetailIntent>, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(viewState.isSearchVisible) }
    val eink = Ember.style.isEink
    val rows = viewState.rows.filter { row -> row.book.title.contains(query, true) || row.book.authors.any { it.contains(query, true) } }
    Column(modifier.fillMaxSize().background(Ember.colors.bg)) {
        EmberTopBar(viewState.seriesName, onBack = { intentDispatcher(SeriesDetailIntent.OnBackClicked) }, actions = {
            TooltipIconButton("Search books", Icons.Default.Search, {
                searchVisible = !searchVisible
                intentDispatcher(SeriesDetailIntent.OnSearchToggled)
            })
        })
        if (searchVisible) SeriesSearch("Search title or author", { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        else LaunchedEffect(Unit) { query = "" }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${viewState.rows.size} books · ${viewState.finishedCount} finished · ${viewState.inProgressCount} in progress", style = Ember.type.meta, color = Ember.colors.ink2)
            EmberProgress(viewState.progress.toFloat(), Ember.style.progressHeightSmall, Modifier.fillMaxWidth())
        }
        PullToRefreshBox(
            isRefreshing = viewState.isRefreshing && !Ember.style.isEink,
            onRefresh = { intentDispatcher(SeriesDetailIntent.OnRefresh) },
            modifier = Modifier.fillMaxSize(),
            indicator = { if (!Ember.style.isEink && viewState.isRefreshing) Text("Refreshing…", Modifier.align(Alignment.TopCenter), color = Ember.colors.ink2) },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (viewState.isRefreshing && eink) item { Text("Refreshing…", color = Ember.colors.ink2) }
                if (viewState.error != null) item("load-error") {
                    EmberEmptyState("Couldn’t load books", "Try again. Your existing books are still shown.",
                        actionLabel = "Try again", onAction = { intentDispatcher(SeriesDetailIntent.OnRefresh) })
                }
                viewState.failedSources.forEach { source -> item("failure:${source.serverId}") { SeriesSourceError(source.name) { intentDispatcher(SeriesDetailIntent.OnRefresh) } } }
                when {
                    viewState.isLoading -> item { Text("Loading books…", style = Ember.type.meta, color = Ember.colors.ink2) }
                    rows.isEmpty() -> item { EmberEmptyState(if (query.isBlank()) "No books in this series" else "No books match ‘$query’",
                        "Try refreshing or another search.", actionLabel = "Try again", onAction = { intentDispatcher(SeriesDetailIntent.OnRefresh) }) }
                    else -> {
                        val numbered = rows.filter { it.position != null }
                        val unnumbered = rows.filter { it.position == null }.sortedBy { it.book.title.lowercase() }
                        if (numbered.isNotEmpty()) item("numbered") { SeriesRows(numbered, intentDispatcher) }
                        if (unnumbered.isNotEmpty()) {
                            item("also-label") { EmberSectionLabel("Also in this series") }
                            item("unnumbered") { SeriesRows(unnumbered, intentDispatcher) }
                        }
                    }
                }
            }
        }
    }
}

private fun seriesNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
private fun format(book: BookUiModel): String = when {
    book.hasReadaloud -> "Read-along"
    book.hasEbook -> "eBook"
    book.hasAudiobook -> "Audiobook"
    else -> "Format unavailable"
}

@Composable
private fun SeriesRows(rows: List<SeriesDetailRow>, dispatch: IntentDispatcher<SeriesDetailIntent>) {
    EmberCard(contentPadding = 0.dp) {
        rows.forEachIndexed { index, row ->
            val percent = ((row.progress ?: 0.0).coerceIn(0.0, 1.0) * 100).toInt()
            val status = when { row.progress == null || row.progress == 0.0 -> "Not started"; row.progress >= 1.0 -> "✓ Finished"; else -> "$percent percent" }
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .clickable(role = Role.Button) { dispatch(SeriesDetailIntent.OnBookClicked(row.book)) }
                .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(row.position?.let { "Book ${seriesNumber(it)}" }, row.book.title, format(row.book), status).joinToString(", ") }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.position?.let { Text(seriesNumber(it), Modifier.width(28.dp), style = Ember.type.screenTitle.copy(fontSize = 17.sp), color = Ember.colors.ink2) }
                SeriesCover(row.book.coverUrl, row.book.title, row.key)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.book.title, style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                    Text((row.book.authors + format(row.book)).joinToString(" · "), style = Ember.type.meta, color = Ember.colors.ink2)
                    if (row.progress != null && row.progress > 0.0 && row.progress < 1.0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EmberProgress(row.progress.toFloat(), Ember.style.progressHeightSmall, Modifier.weight(1f))
                            Text("$percent%", style = Ember.type.meta.copy(fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                        }
                    } else Text(status, style = Ember.type.meta.copy(fontWeight = FontWeight.Bold), color = if (row.progress != null && row.progress >= 1.0) Ember.colors.success else Ember.colors.ink2)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ember.colors.ink2)
            }
            if (index < rows.lastIndex) HorizontalDivider(color = Ember.colors.line)
        }
    }
}
