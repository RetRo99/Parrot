package com.retro99.catalogue.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.nowMillis
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.base.ui.compose.EmberChevron
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.base.ui.compose.EmberEmptyState
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.base.ui.compose.EmberTextField
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.catalogue.ui.add.catalogueDeviceName
import com.retro99.catalogue.ui.cover.CatalogueCover
import com.retro99.catalogue.ui.downloads.ListDownloadState
import com.retro99.catalogue.ui.publication.catalogueMegabytes
import com.retro99.base.ui.compose.EmberProgress
import com.retro99.catalogue.ui.sources.CataloguePasswordField
import com.retro99.translations.PluralRes
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.*

/** What the user can do on the browser screen. Every action has a default so a fixture names only what it needs. */
class CatalogueBrowseActions(
    val onBack: () -> Unit = {},
    val onSearchTextChange: (String) -> Unit = {},
    val onSubmitSearch: () -> Unit = {},
    val onClearSearch: () -> Unit = {},
    val onOpenFilter: (Int) -> Unit = {},
    val onFilterSearchChange: (String) -> Unit = {},
    val onChooseFilter: (Int) -> Unit = {},
    val onCloseFilter: () -> Unit = {},
    val onOpenBook: (String) -> Unit = {},
    val onDownloadBook: (String) -> Unit = {},
    val onCancelDownload: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onSeeAll: (String) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onLoadEarlier: () -> Unit = {},
    val onNearEnd: () -> Unit = {},
    val onNearStart: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCatalogueSettings: () -> Unit = {},
    val onConfirmLocalNetwork: () -> Unit = {},
    val onDismissLocalNetwork: () -> Unit = {},
    val onSignIn: (String, String) -> Unit = { _, _ -> },
    val onDismissSignIn: () -> Unit = {},
)

/**
 * One page of the catalogue [sourceId]; [targetRef] null is its first page. A reference that no
 * longer resolves (the app was restarted) also opens the first page.
 */
@Composable
fun CatalogueBrowseScreen(
    sourceId: String,
    targetRef: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPage: (reference: String) -> Unit = {},
    onOpenBook: (reference: String) -> Unit = {},
    onReplaceWithBook: (reference: String) -> Unit = {},
    onCatalogueSettings: () -> Unit = {},
) {
    val viewModel: CatalogueBrowseViewModel = koinViewModel { parametersOf(sourceId, targetRef.orEmpty()) }
    val browser = viewModel.browser
    val state by browser.state.collectAsState()
    val isEink = Ember.style.isEink
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { browser.onReturn() }
    LaunchedEffect(isEink) { browser.setAutoLoad(!isEink) }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.navigation) {
        when (val navigation = state.navigation) {
            null -> return@LaunchedEffect
            is CatalogueBrowseNavigation.OpenPage -> onOpenPage(viewModel.reference(navigation.place))
            is CatalogueBrowseNavigation.OpenBook -> onOpenBook(viewModel.reference(navigation.book))
            is CatalogueBrowseNavigation.ReplaceWithBook -> onReplaceWithBook(viewModel.reference(navigation.book))
        }
        browser.navigationHandled()
    }
    if (state.closed) return

    // One scroll position per list; it is kept while the screen is in the back stack.
    val listState = key(state.listId) {
        val saved = viewModel.scroll[state.listId]
        rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0).also { list ->
            DisposableEffect(list) {
                onDispose { viewModel.scroll[state.listId] = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            }
        }
    }
    CatalogueBrowseContentScreen(
        state = state,
        actions = remember(browser, onBack, onCatalogueSettings) {
            CatalogueBrowseActions(
                onBack = { if (browser.state.value.searchQuery != null) browser.clearSearch() else onBack() },
                onSearchTextChange = browser::onSearchTextChange,
                onSubmitSearch = browser::submitSearch,
                onClearSearch = browser::clearSearch,
                onOpenFilter = browser::openFilter,
                onFilterSearchChange = browser::onFilterSearchChange,
                onChooseFilter = browser::chooseFilter,
                onCloseFilter = browser::closeFilter,
                onOpenBook = browser::openBook,
                onOpenFolder = browser::openFolder,
                onSeeAll = browser::openSeeAll,
                onLoadMore = browser::loadMore,
                onLoadEarlier = browser::loadEarlier,
                onNearEnd = browser::onNearEnd,
                onNearStart = browser::onNearStart,
                onRetry = browser::retry,
                onCatalogueSettings = onCatalogueSettings,
                onConfirmLocalNetwork = browser::confirmLocalNetwork,
                onDismissLocalNetwork = browser::dismissLocalNetwork,
                onSignIn = browser::signIn,
                onDismissSignIn = browser::dismissSignIn,
            )
        },
        modifier = modifier,
        listState = listState,
    )
}

/** The browser screen for a given state. Fixtures draw this directly. */
@Composable
fun CatalogueBrowseContentScreen(
    state: CatalogueBrowseState,
    actions: CatalogueBrowseActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    nowEpochMillis: Long = nowMillis(),
    deviceName: String = catalogueDeviceName(),
) {
    val catalogue = state.catalogueName
    Column(modifier.fillMaxSize().background(Ember.colors.bg)) {
        if (state.searchQuery != null) {
            SearchTopBar(state.searchText, actions)
        } else {
            EmberTopBar(
                title = state.title ?: catalogue,
                onBack = actions.onBack,
                subtitle = if (state.title == null) stringResource(StringRes.catalogue_type_name_with_protocol) else catalogue,
            )
        }
        when (val content = state.content) {
            CatalogueBrowseContent.FirstLoad, CatalogueBrowseContent.SignInNeeded -> FirstLoad(catalogue)
            is CatalogueBrowseContent.Loaded -> LoadedPage(state, content, actions, listState, nowEpochMillis)
            CatalogueBrowseContent.EmptyFolder -> Message(
                icon = Icons.Outlined.FolderOpen,
                title = stringResource(StringRes.catalogue_empty_folder_title),
                body = stringResource(StringRes.catalogue_empty_folder_body),
                button = stringResource(StringRes.catalogue_go_back),
                onButton = actions.onBack,
            )
            is CatalogueBrowseContent.NoResults -> Message(
                icon = Icons.Outlined.Search,
                title = stringResource(StringRes.catalogue_search_no_results_title, content.query),
                body = stringResource(StringRes.catalogue_search_no_results_body),
                button = stringResource(StringRes.catalogue_clear_search),
                onButton = actions.onClearSearch,
            )
            CatalogueBrowseContent.OfflineNone -> Message(
                icon = Icons.Outlined.WifiOff,
                title = stringResource(StringRes.catalogue_offline_none_title),
                body = stringResource(StringRes.catalogue_offline_none_body),
                button = stringResource(StringRes.catalogue_try_again),
                buttonLabel = stringResource(StringRes.catalogue_a11y_try_loading_again),
                onButton = actions.onRetry,
            )
            CatalogueBrowseContent.RateLimited -> Message(
                icon = Icons.Outlined.Schedule,
                title = stringResource(StringRes.catalogue_rate_limited_title),
                body = stringResource(StringRes.catalogue_rate_limited_body, catalogue),
                button = stringResource(StringRes.catalogue_try_again),
                buttonLabel = stringResource(StringRes.catalogue_a11y_try_loading_again),
                onButton = actions.onRetry,
            )
            is CatalogueBrowseContent.Failed -> Message(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(StringRes.catalogue_page_failed_title),
                body = pageFailureBody(content.reason, catalogue),
                button = stringResource(if (content.reason.canRetry) StringRes.catalogue_try_again else StringRes.catalogue_go_back),
                buttonLabel = if (content.reason.canRetry) stringResource(StringRes.catalogue_a11y_try_loading_again) else null,
                onButton = if (content.reason.canRetry) actions.onRetry else actions.onBack,
                link = stringResource(StringRes.catalogue_catalogue_settings).takeIf { content.reason.offersSettings },
                linkLabel = stringResource(StringRes.catalogue_a11y_open_settings, catalogue),
                onLink = actions.onCatalogueSettings,
            )
        }
    }

    state.filterSheet?.let { FilterSheet(it, actions) }
    state.localNetworkHost?.let { host ->
        EmberDialog(
            onDismissRequest = actions.onDismissLocalNetwork,
            title = stringResource(StringRes.catalogue_local_network_title),
            body = AnnotatedString(stringResource(StringRes.catalogue_local_network_body, catalogue)),
            actions = listOf(
                EmberDialogAction(stringResource(StringRes.catalogue_open), onClick = actions.onConfirmLocalNetwork),
                EmberDialogAction(stringResource(StringRes.catalogue_local_network_dont_open), EmberDialogActionStyle.Main, onClick = actions.onDismissLocalNetwork),
            ),
        ) {
            Text(host, style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    state.signIn?.let { SignInSheet(catalogue, it, actions, deviceName) }
}

@Composable
private fun pageFailureBody(reason: CataloguePageFailure, catalogue: String): String = when (reason) {
    CataloguePageFailure.TimedOut -> stringResource(StringRes.catalogue_page_failed_timed_out, catalogue)
    CataloguePageFailure.CatalogueError -> stringResource(StringRes.catalogue_page_failed_catalogue_error, catalogue)
    CataloguePageFailure.NotAllowed -> stringResource(StringRes.catalogue_page_failed_not_allowed, catalogue)
    CataloguePageFailure.NotFound -> stringResource(StringRes.catalogue_page_failed_not_found, catalogue)
    CataloguePageFailure.TooLarge -> stringResource(StringRes.catalogue_page_failed_too_large)
    CataloguePageFailure.NotACatalogue -> stringResource(StringRes.catalogue_page_failed_not_a_catalogue)
    CataloguePageFailure.Certificate -> stringResource(StringRes.catalogue_page_failed_certificate, catalogue)
}

// --- top of the screen ------------------------------------------------------------------

private val Side = 20.dp

@Composable
private fun SearchTopBar(text: String, actions: CatalogueBrowseActions) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = Side),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(StringRes.general_back), tint = Ember.colors.ink)
        }
        SearchField(
            text = text,
            placeholder = "",
            onTextChange = actions.onSearchTextChange,
            onSubmit = actions.onSubmitSearch,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            active = true,
            onClear = actions.onClearSearch,
        )
    }
}

@Composable
private fun SearchField(
    text: String,
    placeholder: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    filled: Boolean = false,
    onClear: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        textStyle = Ember.type.meta.copy(fontSize = 16.sp, color = Ember.colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        cursorBrush = SolidColor(Ember.colors.accent),
        modifier = modifier.fillMaxWidth().height(50.dp).semantics { if (placeholder.isNotEmpty()) contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxSize().clip(shape).background(if (filled && !Ember.style.isEink) Ember.colors.bg else Ember.colors.surface)
                    .border(if (active || Ember.style.isEink) 2.dp else 1.dp, if (active) Ember.colors.accent else Ember.colors.line, shape)
                    .padding(start = 16.dp, end = if (onClear == null) 16.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!active) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Ember.colors.ink2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(placeholder, style = Ember.type.meta.copy(fontSize = 16.sp), color = Ember.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (onClear != null) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(StringRes.catalogue_clear_search), tint = Ember.colors.ink2)
                    }
                }
            }
        },
    )
}

// --- first load and message screens -----------------------------------------------------

@Composable
private fun FirstLoad(catalogue: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = Side), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(StringRes.catalogue_opening, catalogue),
            style = Ember.type.meta.copy(fontSize = 16.sp),
            color = Ember.colors.ink2,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        // E-ink: text only. Elsewhere still blocks, no shimmer.
        if (!Ember.style.isEink) {
            Placeholder(Modifier.fillMaxWidth().height(48.dp), 18.dp)
            Row(Modifier.horizontalScroll(rememberScrollState(), enabled = false), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { Placeholder(Modifier.size(width = 96.dp, height = 140.dp), 6.dp) }
            }
            repeat(4) { Placeholder(Modifier.fillMaxWidth().height(60.dp), 18.dp) }
        }
    }
}

@Composable
private fun Placeholder(modifier: Modifier, radius: androidx.compose.ui.unit.Dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Ember.colors.track))
}

@Composable
private fun Message(
    icon: ImageVector,
    title: String,
    body: String,
    button: String,
    onButton: () -> Unit,
    buttonLabel: String? = null,
    link: String? = null,
    linkLabel: String? = null,
    onLink: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 44.dp).semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The shared empty and error pattern: icon tile, title, one sentence.
        EmberEmptyState(title = title, message = body, icon = icon, modifier = Modifier.semantics { heading() })
        SoftButton(button, onButton, buttonLabel)
        if (link != null) {
            TextButton(onClick = onLink, modifier = Modifier.padding(top = 6.dp).semantics { if (linkLabel != null) contentDescription = linkLabel }) {
                Text(link, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Ember.colors.accentText)
            }
        }
    }
}

@Composable
private fun SoftButton(text: String, onClick: () -> Unit, label: String? = null) {
    val eink = Ember.style.isEink
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (eink) Ember.colors.accent else Ember.colors.navActive,
            contentColor = if (eink) Ember.colors.onAccent else Ember.colors.navActiveContent,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { if (label != null) contentDescription = label },
    ) {
        Text(text, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

// --- a loaded page ----------------------------------------------------------------------

@Composable
private fun LoadedPage(
    state: CatalogueBrowseState,
    content: CatalogueBrowseContent.Loaded,
    actions: CatalogueBrowseActions,
    listState: LazyListState,
    nowEpochMillis: Long,
) {
    val catalogue = state.catalogueName
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - NEAR_ITEMS
        }
    }
    val nearStart by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex < NEAR_ITEMS } }
    // Asked again after every page: a page that does not fill the screen is followed by the next.
    LaunchedEffect(nearEnd, content.books.size, content.more) { if (nearEnd && content.more == CataloguePaging.Auto) actions.onNearEnd() }
    LaunchedEffect(nearStart, content.books.size, content.earlier) { if (nearStart && content.earlier == CataloguePaging.Auto) actions.onNearStart() }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (state.searchQuery != null) {
            item(key = "search-line") {
                Text(
                    stringResource(StringRes.catalogue_search_in, state.searchQuery, catalogue),
                    style = Ember.type.meta.copy(fontSize = 15.sp),
                    color = Ember.colors.ink2,
                    modifier = Modifier.padding(horizontal = Side).padding(bottom = 10.dp),
                )
            }
        }
        content.savedCopyAt?.let { savedAt ->
            item(key = "offline") { OfflineBanner(timeAgoText(catalogueTimeAgo(savedAt, nowEpochMillis))) }
        }
        if (state.searchAvailable && state.searchQuery == null) {
            item(key = "search") {
                SearchField(
                    text = state.searchText,
                    placeholder = stringResource(StringRes.catalogue_search_catalogue, catalogue),
                    onTextChange = actions.onSearchTextChange,
                    onSubmit = actions.onSubmitSearch,
                    modifier = Modifier.padding(horizontal = Side).padding(bottom = 14.dp),
                )
            }
        }
        if (content.chips.isNotEmpty()) {
            item(key = "chips") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Side).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { content.chips.forEach { chip -> FilterChip(chip) { actions.onOpenFilter(chip.index) } } }
            }
        }
        content.sameBookCount?.let { count ->
            item(key = "same-book") {
                Text(
                    stringResource(StringRes.catalogue_listed_times, catalogue, count),
                    style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = Ember.colors.ink2,
                    modifier = Modifier.padding(horizontal = Side).padding(bottom = 8.dp),
                )
            }
        }
        items(content.shelves, key = { it.key }) { shelf -> Shelf(shelf, actions) }
        if (content.folders.isNotEmpty()) {
            if (content.shelves.isNotEmpty()) {
                item(key = "browse") {
                    EmberSectionLabel(stringResource(StringRes.catalogue_browse), Modifier.padding(horizontal = Side).padding(top = 6.dp, bottom = 10.dp).semantics { heading() })
                }
            }
            item(key = "folders") { Folders(content.folders, actions) }
        }
        if (content.earlier != CataloguePaging.None) {
            item(key = "earlier") { PagingRow(content.earlier, earlier = true, onLoad = actions.onLoadEarlier) }
        }
        items(content.books, key = { it.key }) { book -> BookRow(book, actions) }
        if (content.more != CataloguePaging.None) {
            item(key = "more") { PagingRow(content.more, earlier = false, onLoad = actions.onLoadMore) }
        }
    }
}

private const val NEAR_ITEMS = 4

@Composable
private fun OfflineBanner(savedAgo: String) {
    val shape = RoundedCornerShape(18.dp)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(StringRes.catalogue_offline_banner_lead)) }
        append(" ")
        append(stringResource(StringRes.catalogue_offline_banner_rest, savedAgo))
    }
    Text(
        text,
        style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp),
        color = Ember.colors.ink,
        modifier = Modifier.padding(horizontal = Side).padding(bottom = 12.dp).fillMaxWidth().clip(shape).background(Ember.colors.surface)
            .border(if (Ember.style.isEink) 2.dp else 1.dp, if (Ember.style.isEink) Ember.colors.line else Ember.colors.navActive, shape)
            .padding(horizontal = 15.dp, vertical = 12.dp),
    )
}

@Composable
internal fun timeAgoText(ago: CatalogueTimeAgo): String = when (ago) {
    CatalogueTimeAgo.JustNow -> stringResource(StringRes.catalogue_just_now)
    is CatalogueTimeAgo.Minutes -> stringResource(StringRes.catalogue_time_minutes_ago, ago.count)
    is CatalogueTimeAgo.Hours -> stringResource(StringRes.catalogue_time_hours_ago, ago.count)
    CatalogueTimeAgo.Yesterday -> stringResource(StringRes.catalogue_time_yesterday)
    is CatalogueTimeAgo.Days -> stringResource(StringRes.catalogue_time_days_ago, ago.count)
    is CatalogueTimeAgo.OnDate -> stringResource(StringRes.catalogue_time_on_date, ago.date)
}

@Composable
private fun FilterChip(chip: CatalogueFilterChip, onClick: () -> Unit) {
    val label = stringResource(StringRes.catalogue_a11y_filter_chip, chip.group ?: chip.value, chip.value)
    Row(
        modifier = Modifier.clip(CircleShape).border(if (Ember.style.isEink) 2.dp else 1.dp, Ember.colors.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 15.dp, end = 10.dp).heightIn(min = 38.dp)
            .clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chip.value, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 1)
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = Ember.colors.ink, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

@Composable
private fun Shelf(shelf: CatalogueShelf, actions: CatalogueBrowseActions) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = Side, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shelf.title,
                style = Ember.type.screenTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = Ember.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (shelf.hasSeeAll) {
                val label = stringResource(StringRes.catalogue_a11y_see_all, shelf.title)
                TextButton(onClick = { actions.onSeeAll(shelf.key) }, modifier = Modifier.semantics { contentDescription = label }) {
                    Text(stringResource(StringRes.catalogue_see_all), style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Ember.colors.accentText)
                }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Side).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            shelf.books.forEach { book ->
                val author = book.author ?: stringResource(StringRes.catalogue_unknown_author)
                Column(
                    Modifier.width(96.dp).clickable(role = Role.Button) { actions.onOpenBook(book.key) }
                        .clearAndSetSemantics { contentDescription = "${book.title}, $author" },
                ) {
                    CatalogueCover(book.cover, book.title, Modifier.size(width = 96.dp, height = 140.dp))
                    Text(book.title, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(author, style = Ember.type.meta.copy(fontSize = 13.sp), color = Ember.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Folders(folders: List<CatalogueFolderRow>, actions: CatalogueBrowseActions) {
    val shape = RoundedCornerShape(20.dp)
    val border = if (Ember.style.isEink) 2.dp else 1.dp
    Column(Modifier.padding(horizontal = Side).padding(bottom = 16.dp).fillMaxWidth().clip(shape).background(Ember.colors.surface).border(border, Ember.colors.line, shape)) {
        folders.forEachIndexed { index, folder ->
            if (index > 0) HorizontalDivider(color = Ember.colors.line, thickness = border)
            val label = stringResource(StringRes.catalogue_a11y_folder, folder.title)
            Row(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.onOpenFolder(folder.key) }
                    .heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp).clearAndSetSemantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val tile = RoundedCornerShape(11.dp)
                Box(
                    Modifier.size(36.dp).clip(tile).background(if (Ember.style.isEink) Ember.colors.surface else Ember.colors.navActive)
                        .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, tile) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = if (Ember.style.isEink) Ember.colors.ink else Ember.colors.navActiveContent, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(folder.title, style = Ember.type.meta.copy(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    folder.subtitle?.let {
                        Text(it, style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                EmberChevron()
            }
        }
    }
}

@Composable
private fun tellingText(telling: CatalogueTellingLine): String = buildList {
    telling.editionLabel?.let(::add)
    telling.year?.let { add(if (telling.editionLabel == null) stringResource(StringRes.catalogue_telling_published, it) else it) }
    if (telling.editionLabel == null && telling.year == null) add(stringResource(StringRes.catalogue_telling_none))
    if (telling.fileCount > 0) add(pluralStringResource(PluralRes.catalogue_telling_files, telling.fileCount, telling.fileCount))
}.joinToString(" · ")

@Composable
private fun BookRow(book: CatalogueBookRow, actions: CatalogueBrowseActions) {
    val author = book.author ?: stringResource(StringRes.catalogue_unknown_author)
    val telling = book.telling?.let { tellingText(it) }
    val inLibrary = stringResource(StringRes.catalogue_in_library)
    val download = book.download
    val status = when (download) {
        ListDownloadState.Available -> null
        ListDownloadState.GettingReady -> stringResource(StringRes.catalogue_state_getting_ready)
        ListDownloadState.Waiting -> stringResource(StringRes.catalogue_state_waiting)
        ListDownloadState.Adding -> stringResource(StringRes.catalogue_state_adding)
        ListDownloadState.InLibrary -> inLibrary
        is ListDownloadState.Downloading -> download.total?.takeIf { it > 0 }?.let { "${(download.bytes * 100 / it).coerceIn(0, 100)}%" }
            ?: stringResource(StringRes.catalogue_downloading_so_far, catalogueMegabytes(download.bytes))
    }
    val label = listOfNotNull(book.title, author, telling, status?.removePrefix("✓")?.trim()).joinToString(", ")
    Column(Modifier.fillMaxWidth().padding(horizontal = Side)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(role = Role.Button) { actions.onOpenBook(book.key) }.clearAndSetSemantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
            CatalogueCover(book.cover, book.title, Modifier.size(width = 52.dp, height = 76.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(book.title, style = Ember.type.meta.copy(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(author, style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (telling != null) Text(telling, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                if (download is ListDownloadState.Downloading && download.total != null && download.total > 0) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EmberProgress(progress = (download.bytes.toFloat() / download.total).coerceIn(0f, 1f), modifier = Modifier.weight(1f), height = Ember.style.detailProgressHeight)
                        Text(status.orEmpty(), style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink2)
                    }
                } else if (status != null) Text(status, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = if (download == ListDownloadState.InLibrary) FontWeight.Bold else FontWeight.Normal),
                    color = if (download == ListDownloadState.InLibrary && !Ember.style.isEink) Ember.colors.success else Ember.colors.ink2)
            }
            }
            Box(Modifier.width(ROW_ACTION_WIDTH), contentAlignment = Alignment.CenterEnd) {
                if (download != ListDownloadState.Adding && download != ListDownloadState.InLibrary) {
                    val available = download == ListDownloadState.Available
                    val actionLabel = if (!available) stringResource(StringRes.catalogue_a11y_cancel_download_of, book.title)
                        else if (telling == null) stringResource(StringRes.catalogue_a11y_download, book.title)
                        else stringResource(StringRes.catalogue_a11y_download_telling, book.title, telling)
                    IconButton(onClick = { if (available) actions.onDownloadBook(book.key) else actions.onCancelDownload(book.key) },
                        modifier = Modifier.size(48.dp).border(Ember.style.border, Ember.colors.line, CircleShape)) {
                        Icon(if (available) Icons.Outlined.Download else Icons.Outlined.Close, actionLabel, tint = Ember.colors.ink2, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = Ember.colors.line, thickness = if (Ember.style.isEink) 2.dp else 1.dp)
    }
}

private val ROW_ACTION_WIDTH = 58.dp

/** The row at an end of a book list: loading, the button that loads, or the page that failed. */
@Composable
private fun PagingRow(paging: CataloguePaging, earlier: Boolean, onLoad: () -> Unit) {
    when (paging) {
        CataloguePaging.None -> Unit
        CataloguePaging.Auto, CataloguePaging.Loading -> {
            val label = stringResource(StringRes.catalogue_a11y_loading_more)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp).clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!Ember.style.isEink) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Ember.colors.accent, trackColor = Ember.colors.track, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(StringRes.catalogue_loading_more), style = Ember.type.meta.copy(fontSize = 15.sp), color = Ember.colors.ink2)
            }
        }
        CataloguePaging.Button -> {
            val label = stringResource(if (earlier) StringRes.catalogue_load_earlier else StringRes.catalogue_a11y_load_more)
            OutlinedButton(
                onClick = onLoad,
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(if (Ember.style.isEink) 2.dp else 1.dp, Ember.colors.line),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ember.colors.ink),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Side, vertical = 12.dp).height(54.dp).semantics { contentDescription = label },
            ) {
                Text(stringResource(if (earlier) StringRes.catalogue_load_earlier else StringRes.catalogue_load_more), style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
        }
        CataloguePaging.Failed -> {
            val shape = RoundedCornerShape(18.dp)
            val eink = Ember.style.isEink
            val text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(StringRes.catalogue_list_failed_lead)) }
                append(" ")
                append(stringResource(if (earlier) StringRes.catalogue_list_failed_earlier_rest else StringRes.catalogue_list_failed_rest))
            }
            val retryLabel = stringResource(StringRes.catalogue_a11y_try_loading_more)
            Row(
                modifier = Modifier.padding(horizontal = Side, vertical = 12.dp).fillMaxWidth().clip(shape)
                    .background(if (eink) Ember.colors.surface else Ember.colors.errorContainer)
                    .then(if (eink) Modifier.border(2.dp, Ember.colors.line, shape) else Modifier)
                    .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 20.sp), color = if (eink) Ember.colors.ink else Ember.colors.error, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onLoad,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.surface, contentColor = Ember.colors.ink),
                    border = androidx.compose.foundation.BorderStroke(if (eink) 2.dp else 1.dp, Ember.colors.line),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier.semantics { contentDescription = retryLabel },
                ) { Text(stringResource(StringRes.catalogue_try_again), style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)) }
            }
        }
    }
}

// --- sheets -----------------------------------------------------------------------------

@Composable
private fun FilterSheet(sheet: CatalogueFilterSheet, actions: CatalogueBrowseActions) {
    val group = sheet.group.orEmpty()
    EmberBottomSheet(onDismiss = actions.onCloseFilter) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = Side).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group, style = Ember.type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp), color = Ember.colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
                Text(pluralStringResource(PluralRes.catalogue_filter_options, sheet.optionCount, sheet.optionCount), style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.ink2)
            }
            if (sheet.searchable) {
                SearchField(
                    text = sheet.searchText,
                    placeholder = stringResource(StringRes.catalogue_filter_search, group.lowercase()),
                    onTextChange = actions.onFilterSearchChange,
                    onSubmit = {},
                    modifier = Modifier.padding(bottom = 8.dp),
                    filled = true,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).selectableGroup()) {
                items(sheet.options, key = { it.index }) { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(selected = option.selected, role = Role.RadioButton) { actions.onChooseFilter(option.index) }
                            .heightIn(min = 52.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Radio(option.selected)
                        Text(
                            option.title,
                            style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Normal),
                            color = Ember.colors.ink,
                            modifier = Modifier.weight(1f).padding(start = 14.dp),
                        )
                        option.count?.let { Text(groupedNumber(it), style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.ink2) }
                    }
                    HorizontalDivider(color = Ember.colors.line, thickness = if (Ember.style.isEink) 2.dp else 1.dp)
                }
            }
        }
    }
}

/** 41208 as "41,208": the count the catalogue gives, only grouped for reading. */
internal fun groupedNumber(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed().replace("-,", "-")

@Composable
private fun Radio(selected: Boolean) {
    val eink = Ember.style.isEink
    val ring = if (selected) Ember.colors.accent else Ember.colors.ink2
    Box(Modifier.size(22.dp).border(2.dp, if (eink) Ember.colors.ink else ring, CircleShape), contentAlignment = Alignment.Center) {
        // E-ink: a filled black radio.
        if (selected) Box(Modifier.size(if (eink) 14.dp else 10.dp).clip(CircleShape).background(if (eink) Ember.colors.ink else Ember.colors.accent))
    }
}

@Composable
internal fun SignInSheet(catalogue: String, signIn: CatalogueSignInState, actions: CatalogueBrowseActions, deviceName: String) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // Wrong details: the password is cleared and gets the focus.
    LaunchedEffect(signIn.wrongDetails) { if (signIn.wrongDetails) password = "" }
    val passwordFocus = remember { FocusRequester() }
    EmberBottomSheet(onDismiss = actions.onDismissSignIn) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(StringRes.catalogue_sign_in_title, catalogue), style = Ember.type.screenTitle.copy(fontSize = 25.sp, lineHeight = 31.sp), color = Ember.colors.ink)
            Text(stringResource(StringRes.catalogue_sign_in_body, deviceName), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ember.colors.ink2)
            EmberTextField(
                value = username,
                onValueChange = { username = it },
                label = stringResource(StringRes.catalogue_username),
                labelStyle = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                enabled = !signIn.working,
                isError = signIn.wrongDetails,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            CataloguePasswordField(
                value = password,
                onValueChange = { password = it },
                enabled = !signIn.working,
                error = signIn.wrongDetails,
                errorText = stringResource(StringRes.catalogue_add_error_wrong_credentials),
                focusRequester = passwordFocus,
            )
            Text(stringResource(StringRes.catalogue_password_helper), style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Ember.colors.ink2)
            Button(
                onClick = { actions.onSignIn(username, password) },
                enabled = !signIn.working,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent),
            ) {
                if (signIn.working && !Ember.style.isEink) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Ember.colors.onAccent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    stringResource(if (signIn.working) StringRes.catalogue_add_signing_in else StringRes.catalogue_sign_in),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
