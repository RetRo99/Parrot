package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.translations.PluralRes
import com.retro99.translations.StringRes
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_close
import resources.translations.reader_bookmark_deleted
import resources.translations.reader_bookmark_undo
import resources.translations.reader_bookmarks_title
import resources.translations.reader_contents_book_percent
import resources.translations.reader_contents_bookmarks_count
import resources.translations.reader_contents_chapters
import resources.translations.reader_contents_chapter_quantity
import resources.translations.reader_contents_find
import resources.translations.reader_find_clear
import resources.translations.reader_overlay_contents
import resources.translations.reader_toc_search_hint

/** Duration of the bookmark-deletion undo snackbar inside the sheet. */
private const val BOOKMARK_DELETE_UNDO_MS = 5_000L

/**
 * The reader's Contents sheet: one sheet with Chapters and Bookmarks tabs, a header that
 * honestly says where you are in the book, and no jump-to-percentage card. Every jump
 * (chapter, group or bookmark) closes the sheet.
 */
@Composable
internal fun ReaderContentsSheet(
    state: ReaderViewState,
    onChapterClick: (href: String) -> Unit,
    onBookmarkClick: (BookmarkUiModel) -> Unit,
    onAddBookmark: () -> Unit,
    onBookmarkDelete: (String) -> Unit,
    onBookmarkRename: (String, String) -> Unit,
    onRestoreBookmark: (BookmarkUiModel) -> Unit,
    onBookmarkDeletedDismissed: () -> Unit,
    onToggleGroup: (Int) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val toc = state.tableOfContents
    val currentPosition = state.currentPosition
    val location = remember(toc, currentPosition?.href, currentPosition?.progression, state.bookSearchReadingOrder) {
        findTocLocation(toc, currentPosition?.href, currentPosition?.progression, state.bookSearchReadingOrder)
    }
    var selectedTab by remember { mutableStateOf(state.contentsInitialTab) }
    val showSearchIcon = toc.size > LARGE_TOC_THRESHOLD
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var submittedQuery by remember { mutableStateOf("") }
    // E-ink keyboards flicker: only filter once the reader submits.
    val activeQuery = if (eink) submittedQuery else query.text
    val matches = remember(toc, activeQuery) {
        if (activeQuery.isBlank()) emptyList() else filterTocByTitle(toc, activeQuery)
    }

    EmberBottomSheet(onDismiss = onDismiss, footer = footer) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxSheetHeight = maxHeight * 0.88f
            Column(
                Modifier
                    .fillMaxWidth()
                    // Fixed geometry: Chapters, Bookmarks, search and empty states all share one
                    // sheet height, so switching tabs never resizes (jumps) the modal.
                    .height(maxSheetHeight)
                    .then(if (footer == null) Modifier.navigationBarsPadding() else Modifier)
                    .padding(bottom = 8.dp),
            ) {
                ContentsHeader(
                    chapterTitle = location?.item?.title ?: currentPosition?.title.orEmpty(),
                    bookPercent = currentPosition?.totalProgression?.let { bookPercent(it) },
                    showSearch = showSearchIcon,
                    searchOpen = searchOpen,
                    onToggleSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) {
                            query = TextFieldValue("")
                            submittedQuery = ""
                        }
                    },
                    onDismiss = onDismiss,
                )
                ContentsTabs(
                    selectedTab = selectedTab,
                    bookmarkCount = state.bookmarks.size,
                    onSelect = {
                        selectedTab = it
                        if (it != ContentsTab.CHAPTERS && searchOpen) {
                            searchOpen = false
                            query = TextFieldValue("")
                            submittedQuery = ""
                        }
                    },
                )
                val chaptersSearching = selectedTab == ContentsTab.CHAPTERS && searchOpen
                if (chaptersSearching) {
                    FindChapterField(
                        query = query,
                        matchCount = if (activeQuery.isBlank()) null else matches.size,
                        onQueryChange = { value ->
                            query = value
                            // Clearing restores the tree on every display; e-ink otherwise filters on submit.
                            if (!eink || value.text.isEmpty()) submittedQuery = value.text
                        },
                        onSubmit = { submittedQuery = query.text },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        ContentsTab.CHAPTERS -> if (chaptersSearching) {
                            ContentsSearchResults(
                                toc = toc,
                                matches = if (activeQuery.isBlank()) emptyList() else matches,
                                isEink = eink,
                                onNavigateToHref = onChapterClick,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ContentsChaptersTab(
                                toc = toc,
                                expandedGroups = state.contentsExpandedGroups,
                                currentFlatIndex = location?.flatIndex,
                                chapterInfo = state.chapterInfo,
                                chapterReadingTimeInfo = state.chapterReadingTimeInfo,
                                resourceProgression = currentPosition?.progression,
                                isEink = eink,
                                onToggleGroup = onToggleGroup,
                                onNavigateToHref = onChapterClick,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        ContentsTab.BOOKMARKS -> ContentsBookmarksTab(
                            bookmarks = state.bookmarks,
                            toc = toc,
                            readingOrder = state.bookSearchReadingOrder,
                            currentPosition = currentPosition,
                            isEink = eink,
                            onBookmarkClick = onBookmarkClick,
                            onAddBookmark = onAddBookmark,
                            onDelete = onBookmarkDelete,
                            onRename = onBookmarkRename,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    BookmarkDeletedSnackbar(
                        show = state.showBookmarkDeleted,
                        deleted = state.lastDeletedBookmark,
                        onRestore = onRestoreBookmark,
                        onDismissed = onBookmarkDeletedDismissed,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentsHeader(
    chapterTitle: String,
    bookPercent: Int?,
    showSearch: Boolean,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Ember.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(StringRes.reader_overlay_contents),
                style = Ember.type.cardTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
                color = colors.ink,
            )
            val percentText = bookPercent?.let { stringResource(StringRes.reader_contents_book_percent, it) }
            val subtitle = listOfNotNull(
                chapterTitle.trim().takeIf { it.isNotEmpty() },
                percentText,
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = colors.ink2,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                )
            }
        }
        if (showSearch) {
            IconButton(onClick = onToggleSearch, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(StringRes.reader_contents_find),
                    tint = if (searchOpen) colors.accentText else colors.ink,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Close, stringResource(StringRes.general_close), tint = colors.ink)
        }
    }
}

@Composable
private fun ContentsTabs(
    selectedTab: ContentsTab,
    bookmarkCount: Int,
    onSelect: (ContentsTab) -> Unit,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(shape)
            .background(colors.bg)
            .border(1.dp, colors.chipBorder, shape)
            .padding(3.dp)
            .semantics { selectableGroup() },
    ) {
        TabSegment(
            text = stringResource(StringRes.reader_contents_chapters),
            selected = selectedTab == ContentsTab.CHAPTERS,
            isEink = eink,
            onClick = { onSelect(ContentsTab.CHAPTERS) },
            modifier = Modifier.weight(1f),
        )
        TabSegment(
            text = if (bookmarkCount > 0) {
                stringResource(StringRes.reader_contents_bookmarks_count, bookmarkCount)
            } else {
                stringResource(StringRes.reader_bookmarks_title)
            },
            selected = selectedTab == ContentsTab.BOOKMARKS,
            isEink = eink,
            onClick = { onSelect(ContentsTab.BOOKMARKS) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabSegment(
    text: String,
    selected: Boolean,
    isEink: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(
                when {
                    selected && isEink -> colors.ink
                    selected -> colors.accent
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .semantics { role = Role.Tab; contentDescription = text }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = when {
                selected && isEink -> colors.surface
                selected -> colors.onAccent
                else -> colors.ink2
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
        )
    }
}

/** The "Find a chapter" field under the tabs: 48dp, focused on open, with a live match count. */
@Composable
private fun FindChapterField(
    query: TextFieldValue,
    matchCount: Int?,
    onQueryChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = query,
        onValueChange = { onQueryChange(it) },
        singleLine = true,
        textStyle = Ember.type.meta.copy(color = colors.ink, fontSize = 16.sp),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .height(48.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.bg, shape)
                    .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.chipBorder, shape)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = colors.ink2, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (query.text.isEmpty()) {
                        Text(
                            stringResource(StringRes.reader_toc_search_hint),
                            style = Ember.type.meta.copy(fontSize = 16.sp),
                            color = colors.ink2,
                        )
                    }
                    inner()
                }
                if (matchCount != null) {
                    Text(
                        text = pluralStringResource(PluralRes.reader_contents_chapter_quantity, matchCount, matchCount),
                        color = colors.ink2,
                        style = Ember.type.meta,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = { onQueryChange(TextFieldValue("")) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        stringResource(StringRes.reader_find_clear),
                        tint = colors.ink2,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

/** Undo bar for deletion inside the sheet; the bookmark is restored on Undo. */
@Composable
private fun BookmarkDeletedSnackbar(
    show: Boolean,
    deleted: BookmarkUiModel?,
    onRestore: (BookmarkUiModel) -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!show || deleted == null) return
    LaunchedEffect(deleted.id) {
        delay(BOOKMARK_DELETE_UNDO_MS)
        onDismissed()
    }
    Snackbar(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        action = {
            TextButton(onClick = { onRestore(deleted) }) {
                Text(stringResource(StringRes.reader_bookmark_undo))
            }
        },
    ) {
        Text(stringResource(StringRes.reader_bookmark_deleted))
    }
}
