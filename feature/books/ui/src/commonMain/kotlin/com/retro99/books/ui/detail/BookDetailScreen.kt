package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.books.domain.model.BookType
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import com.retro99.reader.domain.usecase.ResolveSavedBookUseCase
import com.retro99.saved.domain.usecase.ObserveBookSavedItemsUseCase
import org.jetbrains.compose.resources.pluralStringResource
import resources.translations.Res
import resources.translations.book_detail_saved_bookmarks
import resources.translations.book_detail_saved_highlights
import resources.translations.book_detail_saved_notes
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.books_detail_add_favorite
import resources.translations.books_detail_remove_favorite
import resources.translations.book_detail_conflict_error
import resources.translations.book_detail_loading
import androidx.compose.material3.Text

@Composable
fun BookDetailScreen(
    serverId: String,
    bookUuid: String,
    onNavigateToReader: (
        serverId: String,
        bookUuid: String,
        bookType: BookType,
        bookTitle: String,
        linkedResumeResolved: Boolean,
        listenMode: Boolean,
    ) -> Unit,
    onNavigateToSeriesDetail: (seriesUuid: String, seriesName: String) -> Unit,
    onBack: () -> Unit,
    onNavigateToLinkPicker: (serverId: String, bookUuid: String) -> Unit,
    onNavigateToBookDetail: (serverId: String, bookUuid: String) -> Unit,
    onNavigateToPositions: (serverId: String, bookUuid: String) -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToSavedItems: (String) -> Unit,
    bottomNavigationHeight: Dp = 0.dp,
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel = koinViewModel {
        parametersOf(serverId, bookUuid, onNavigateToReader, onNavigateToSeriesDetail,
            onBack, onNavigateToLinkPicker, onNavigateToBookDetail, onNavigateToPositions)
    },
) {
    val resolveSavedBook = koinInject<ResolveSavedBookUseCase>()
    val observeSavedItems = koinInject<ObserveBookSavedItemsUseCase>()
    val savedSummary by produceState<Pair<String, Triple<Int, Int, Int>>?>(null, serverId, bookUuid) {
        val identity = resolveSavedBook(serverId, bookUuid)
        observeSavedItems(setOf(identity.selfKey), setOf(bookUuid)).collect { items ->
            value = identity.selfKey to Triple(
                items.count { it.type.name == "Bookmark" },
                items.count { it.type.name == "Highlight" },
                items.count { it.hasNote },
            )
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onIntent(BookDetailIntent.OnReturnedToDetail)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BaseScreen(modifier = modifier, viewModel = viewModel) { state, dispatch ->
        BookDetailContent(state, dispatch, onNavigateToServers, bottomNavigationHeight,
            savedSummary, onNavigateToSavedItems)
    }
}

@Composable
private fun BookDetailContent(
    state: BookDetailViewState,
    dispatch: IntentDispatcher<BookDetailIntent>,
    onServers: () -> Unit,
    bottomNavigationHeight: Dp,
    savedSummary: Pair<String, Triple<Int, Int, Int>>?,
    onSavedItems: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var manage by remember(state.book?.uuid) { mutableStateOf(false) }
    val fallbackError = stringResource(StringRes.book_detail_conflict_error)
    LaunchedEffect(state.conflictResolutionError) {
        state.conflictResolutionError?.let { error ->
            snackbar.showSnackbar(error.message ?: fallbackError)
            dispatch(BookDetailIntent.OnConflictResolutionErrorDismissed)
        }
    }
    LaunchedEffect(state.bookFileTransferError) {
        state.bookFileTransferError?.let { error -> snackbar.showSnackbar(error) }
    }
    val media = state.detailMedia()
    Box(Modifier.fillMaxSize().background(Ember.colors.bg)) {
        if (!Ember.style.isEink) Box(Modifier.fillMaxWidth().height(330.dp)
            .background(Brush.verticalGradient(listOf(Ember.colors.bookHero, Ember.colors.bg))))
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = {
                if (Ember.style.isEink) {
                    snackbar.currentSnackbarData?.let { data -> Snackbar(data) }
                } else SnackbarHost(snackbar)
            },
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { dispatch(BookDetailIntent.OnBackClicked) },
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(StringRes.general_back), tint = Ember.colors.ink)
                    }
                    if (state.book != null && state.error == null) {
                        IconButton(onClick = { dispatch(BookDetailIntent.OnFavoriteClicked) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(if (state.isFavorite) Icons.Filled.Favorite
                                else Icons.Outlined.FavoriteBorder,
                                stringResource(if (state.isFavorite)
                                    StringRes.books_detail_remove_favorite
                                    else StringRes.books_detail_add_favorite),
                                tint = Ember.colors.accentText)
                        }
                    }
                }
            },
        ) { padding ->
            when {
                state.isLoading -> if (Ember.style.isEink) {
                    Text(stringResource(StringRes.book_detail_loading),
                        modifier = Modifier.padding(padding).padding(20.dp),
                        color = Ember.colors.ink, style = Ember.type.meta)
                } else Box(Modifier.padding(padding)) { LoadingScreen() }
                state.book != null -> Column(
                    Modifier.fillMaxSize().padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp,
                            bottom = bottomNavigationHeight + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    BookDetailHeader(state.book, media) { series ->
                        dispatch(BookDetailIntent.OnSeriesClicked(series))
                    }
                    BookDetailReadingSection(state, dispatch) {
                        BookDetailActions(state, media, dispatch)
                    }
                    savedSummary?.let { (key, counts) ->
                        val subtitle = listOfNotNull(
                            counts.first.takeIf { it > 0 }?.let {
                                pluralStringResource(Res.plurals.book_detail_saved_bookmarks, it, it) },
                            counts.second.takeIf { it > 0 }?.let {
                                pluralStringResource(Res.plurals.book_detail_saved_highlights, it, it) },
                            counts.third.takeIf { it > 0 }?.let {
                                pluralStringResource(Res.plurals.book_detail_saved_notes, it, it) },
                        ).joinToString(" · ")
                        if (counts.first + counts.second + counts.third > 0) {
                            BookDetailSavedRow(subtitle, onClick = { onSavedItems(key) })
                        }
                    }
                    BookLocationsCard(state, media, dispatch, onManage = { manage = true })
                    BookDetailAbout(state.book.description)
                    BookDetailFacts(state)
                }
                state.error != null -> BookDetailError(
                    onRetry = { dispatch(BookDetailIntent.OnRetryClicked) },
                    onServers = onServers,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
    BookDetailDialogs(state, dispatch)
    if (manage && state.book != null) {
        BookManageSheet(state, media, dispatch, onDismiss = { manage = false })
    }
}
