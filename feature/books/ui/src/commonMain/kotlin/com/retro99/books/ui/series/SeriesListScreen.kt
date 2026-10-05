package com.retro99.books.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.*
import com.retro99.books.ui.series.model.SeriesListUiModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SeriesListScreen(
    onNavigateToSeriesDetail: (SeriesListUiModel) -> Unit = {},
    modifier: Modifier = Modifier,
    onConnectServer: () -> Unit = {},
    viewModel: SeriesListViewModel = koinViewModel { parametersOf(onNavigateToSeriesDetail) },
) {
    BaseScreen(modifier = modifier, viewModel = viewModel) { state, dispatch ->
        LaunchedEffect(Unit) { dispatch(SeriesListIntent.OnScreenVisible) }
        SeriesListScreenContent(state, dispatch, onConnectServer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesListScreenContent(
    viewState: SeriesListViewState,
    intentDispatcher: IntentDispatcher<SeriesListIntent>,
    onConnectServer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val eink = Ember.style.isEink
    val filtered = viewState.series.filter { it.name.contains(query.trim(), true) }
    Column(modifier.fillMaxSize().background(Ember.colors.bg)) {
        EmberTopBar("Browse")
        SeriesSearch("Search series", { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        PullToRefreshBox(
            isRefreshing = viewState.isRefreshing && !Ember.style.isEink,
            onRefresh = { intentDispatcher(SeriesListIntent.OnRefresh) },
            modifier = Modifier.fillMaxSize(),
            indicator = { if (!Ember.style.isEink && viewState.isRefreshing) Text("Refreshing…", Modifier.align(Alignment.TopCenter), color = Ember.colors.ink2) },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (viewState.isRefreshing && eink) item { Text("Refreshing…", color = Ember.colors.ink2) }
                if (viewState.error != null) item("load-error") {
                    EmberEmptyState("Couldn’t load series", "Try again. Your existing series are still shown.",
                        actionLabel = "Try again", onAction = { intentDispatcher(SeriesListIntent.OnRefresh) })
                }
                viewState.failedSources.forEach { source -> item("failure:${source.serverId}") {
                    SeriesSourceError(source.name) { intentDispatcher(SeriesListIntent.OnRefresh) }
                } }
                when {
                    viewState.isLoading -> item { Text("Loading series…", style = Ember.type.meta, color = Ember.colors.ink2) }
                    filtered.isEmpty() -> item {
                        EmberEmptyState(
                            title = if (query.isNotBlank()) "No series match ‘$query’" else "No series yet",
                            message = if (query.isNotBlank()) "Try another search." else
                                "Series come from your Storyteller or Audiobookshelf library. Books added from this phone don’t have series information yet.",
                            icon = Icons.Outlined.CollectionsBookmark,
                        )
                        if (!viewState.hasConnectedServer && query.isBlank()) Button(onClick = onConnectServer,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), elevation = null,
                            border = if (eink) BorderStroke(2.dp, Ember.colors.line) else null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (eink) Ember.colors.surface else Ember.colors.navActive,
                                contentColor = if (eink) Ember.colors.ink else Ember.colors.navActiveContent)) {
                            Text("Connect a server")
                        }
                    }
                }
                items(filtered, key = { it.uuid }) { series ->
                    EmberCard(onClick = { intentDispatcher(SeriesListIntent.OnSeriesClicked(series)) }, contentPadding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SeriesCover(series.coverUrl, series.name, series.uuid)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(series.name, style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                                Text(buildList {
                                    add("${series.bookCount} ${if (series.bookCount == 1) "book" else "books"}")
                                    add(if (series.finishedCount > 0) "${series.finishedCount} finished" else "not started".takeIf { series.progress == 0.0 } ?: "in progress")
                                    series.author?.let { add(it) }
                                }.joinToString(" · "), style = Ember.type.meta, color = Ember.colors.ink2)
                                EmberProgress(series.progress.toFloat(), Ember.style.progressHeightSmall, Modifier.fillMaxWidth())
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ember.colors.ink2)
                        }
                    }
                }
            }
        }
    }
}
