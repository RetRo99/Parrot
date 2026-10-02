package com.retro99.reader.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.base.ui.compose.literataFamily
import com.retro99.translations.StringRes
import com.retro99.translations.PluralRes
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import resources.translations.*
import kotlin.math.roundToInt

@Composable
internal fun searchChapterLabel(chapter: SearchChapter): String = chapter.title
    ?: stringResource(if (chapter.beginning) StringRes.reader_find_beginning else StringRes.reader_find_other)

@Composable
private fun searchMatchCount(count: Int): String = pluralStringResource(PluralRes.reader_find_match_quantity, count, count)

private sealed interface SearchRow {
    val key: String
    data class Header(val chapter: SearchChapter, val count: Int, val firstIndex: Int) : SearchRow { override val key = "chapter-${chapter.key}-$firstIndex" }
    data class Hit(val hit: SearchHit) : SearchRow { override val key = "hit-${hit.result.sessionId}-${hit.result.index}" }
    /** Always the same offer to search past the spoiler boundary, matches or not. */
    data object SearchRest : SearchRow { override val key = "search-rest" }
    data class RestDivider(val firstIndex: Int) : SearchRow { override val key = "rest-$firstIndex" }
}

@Composable
internal fun ReaderSearchSheet(
    state: ReaderViewState,
    dispatch: (ReaderIntent) -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val hits = remember(state.bookSearchResults, state.tableOfContents, state.bookSearchReadingOrder, state.bookSearchBoundaries) {
        presentSearchHits(state.bookSearchResults, state.tableOfContents, state.bookSearchReadingOrder, state.bookSearchBoundaries)
    }
    // Spoiler protection: until the reader asks, only the matches up to the boundary exist.
    val boundary = state.searchBoundary
    val hidden = boundary != null && !state.searchAheadRevealed
    val splitIndex = state.searchAheadSplitIndex
    val preHits = if (splitIndex == null) hits else hits.take(splitIndex)
    val afterHits = if (splitIndex == null) emptyList() else hits.drop(splitIndex)
    val rows = buildList {
        var previous: String? = null
        val preCounts = preHits.groupingBy { it.chapter.key }.eachCount()
        preHits.forEach { hit ->
            if (hit.chapter.key != previous) add(SearchRow.Header(hit.chapter, preCounts.getValue(hit.chapter.key), hit.result.index))
            add(SearchRow.Hit(hit))
            previous = hit.chapter.key
        }
        if (hidden) add(SearchRow.SearchRest)
        if (state.searchAheadRevealed) {
            add(SearchRow.RestDivider(splitIndex ?: 0))
            previous = null
            val afterCounts = afterHits.groupingBy { it.chapter.key }.eachCount()
            afterHits.forEach { hit ->
                if (hit.chapter.key != previous) add(SearchRow.Header(hit.chapter, afterCounts.getValue(hit.chapter.key), hit.result.index))
                add(SearchRow.Hit(hit))
                previous = hit.chapter.key
            }
        }
    }
    val chapterCount = hits.map { it.chapter.key }.toSet().size
    val fullySearched = state.bookSearchComplete && (boundary == null || state.searchAheadComplete)
    val summary = when {
        state.isBookSearchLoading -> stringResource(StringRes.reader_find_running, state.bookSearchCount)
        // Nothing found and nothing searched past the boundary yet says exactly that.
        hidden && state.bookSearchComplete && state.bookSearchCount == 0 -> stringResource(StringRes.reader_find_upto_none)
        hidden -> stringResource(
            StringRes.reader_find_upto_summary,
            searchMatchCount(state.bookSearchCount),
            boundary?.percent() ?: 0,
        )

        fullySearched -> stringResource(
            StringRes.reader_find_summary,
            searchMatchCount(state.bookSearchCount),
            pluralStringResource(PluralRes.reader_find_chapter_quantity, chapterCount, chapterCount),
        )

        else -> stringResource(
            StringRes.reader_find_partial,
            searchMatchCount(state.bookSearchCount),
            pluralStringResource(PluralRes.reader_find_chapter_quantity, chapterCount, chapterCount),
        )
    }
    val listState = rememberLazyListState()
    var scrolled by remember(state.bookSearchQuery) { mutableStateOf(false) }
    LaunchedEffect(rows.size, state.bookSearchComplete) {
        if (!scrolled) {
            val target = state.selectedSearchIndex?.let { selected ->
                rows.indexOfFirst { it is SearchRow.Hit && it.hit.result.index == selected }
            } ?: -1
            if (target >= 0) {
                listState.scrollToItem(target)
                scrolled = true
            }
        }
    }
    EmberBottomSheet(onDismiss = { dispatch(ReaderIntent.ToggleBookSearch) }, footer = footer) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Keep the same sheet geometry for idle, pending, partial, complete and empty states.
            // Only the list's contents change; its natural height must not resize the modal.
            Column(Modifier.fillMaxWidth().height(maxHeight * 0.86f).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(StringRes.reader_search_title), style = Ember.type.screenTitle, color = colors.ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = { dispatch(ReaderIntent.ToggleBookSearch) }) {
                        Icon(Icons.Default.Close, stringResource(StringRes.reader_find_close), tint = colors.ink)
                    }
                }
                SearchQueryField(state.bookSearchQuery,
                    onChange = { dispatch(ReaderIntent.SearchBook(it, submitOnly = eink)) },
                    onSubmit = { dispatch(ReaderIntent.SubmitBookSearch) })
                when {
                    !state.bookSearchAvailable -> SearchMessage(stringResource(StringRes.reader_find_unavailable), stringResource(if (state.bookSearchNoTextLayer) StringRes.reader_find_no_text_layer else StringRes.reader_find_unavailable_hint))
                    state.bookSearchQuery.isBlank() -> {
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(StringRes.reader_find_recents), style = Ember.type.eyebrow, color = colors.accentText, modifier = Modifier.weight(1f))
                            TextButton(onClick = { dispatch(ReaderIntent.ClearBookSearchRecents) }) { Text(stringResource(StringRes.reader_find_clear), color = colors.ink2) }
                        }
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(state.bookSearchRecents, key = { it.query }) { recent ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    dispatch(ReaderIntent.SearchBook(recent.query, submitOnly = true))
                                    dispatch(ReaderIntent.SubmitBookSearch)
                                }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, null, tint = colors.ink2, modifier = Modifier.size(18.dp))
                                    Text(recent.query, color = colors.ink, modifier = Modifier.weight(1f).padding(start = 16.dp))
                                }
                                HorizontalDivider(color = colors.line)
                            }
                        }
                        Text(stringResource(StringRes.reader_find_hint) + if (state.bookSearchIgnoresCaseAndAccents) "\n" + stringResource(StringRes.reader_find_insensitive) else "",
                            color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(top = 16.dp, bottom = 28.dp))
                    }
                    state.bookSearchQuery.trim().length < 2 -> SearchMessage(stringResource(StringRes.reader_find_minimum))
                    state.bookSearchResults.isEmpty() && !state.isBookSearchLoading && !state.bookSearchComplete && !state.bookSearchFailed ->
                        SearchMessage(stringResource(if (eink) StringRes.reader_find_submit else StringRes.reader_find_retry))
                    else -> {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(summary, color = colors.ink, style = Ember.type.label, modifier = Modifier.weight(1f)
                                .semantics { if (fullySearched) liveRegion = LiveRegionMode.Polite })
                        }
                        if (state.bookSearchFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(StringRes.reader_find_failed), color = colors.ink2, modifier = Modifier.weight(1f))
                            TextButton(onClick = { dispatch(ReaderIntent.SubmitBookSearch) }) { Text(stringResource(StringRes.reader_find_retry)) }
                        }
                        if (state.bookSearchCapped) Text(stringResource(StringRes.reader_find_cap, SEARCH_RESULT_LIMIT), color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(bottom = 8.dp))
                        // With nothing hidden left to look for, the plain empty state is enough.
                        if (boundary == null && fullySearched && hits.isEmpty()) {
                            SearchMessage(stringResource(StringRes.reader_find_empty, state.bookSearchQuery.trim()), stringResource(StringRes.reader_find_empty_hint))
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(rows, key = { it.key }) { row -> when (row) {
                                    is SearchRow.Header -> Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
                                        // Chapter title only: the TOC ordinal told readers nothing.
                                        Text(searchChapterLabel(row.chapter), color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(searchMatchCount(row.count), color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                    }
                                    is SearchRow.Hit -> SearchResultCard(row.hit) { dispatch(ReaderIntent.GoToSearchResult(row.hit.result)) }
                                    SearchRow.SearchRest -> SearchRestRow { dispatch(ReaderIntent.RevealSearchAhead()) }
                                    is SearchRow.RestDivider -> RestDividerRow(
                                        afterCount = afterHits.size,
                                        loading = state.isSearchAheadLoading,
                                        complete = state.searchAheadComplete,
                                        eink = eink,
                                    )
                                } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one neutral offer to search past the spoiler boundary. It looks exactly the same
 * whether the rest of the book holds zero matches or five hundred: its presence, or any
 * count near it, must never tell the reader what is coming.
 */
@Composable
private fun SearchRestRow(onClick: () -> Unit) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(if (eink) 1.5.dp else 1.dp, colors.chipBorder),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(stringResource(StringRes.reader_find_search_rest), color = colors.ink, style = Ember.type.label)
            Text(stringResource(StringRes.reader_find_spoiler_warning),
                color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Default.KeyboardArrowDown, stringResource(StringRes.reader_find_search_rest), tint = colors.accentText)
    }
}

/** Everything below this line is past the reader's page; shown only after the reveal. */
@Composable
private fun RestDividerRow(afterCount: Int, loading: Boolean, complete: Boolean, eink: Boolean) {
    val colors = Ember.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(StringRes.reader_find_after_page), color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (loading) {
                Text(stringResource(StringRes.reader_find_running, afterCount), color = colors.ink2, style = Ember.type.meta)
            } else if (afterCount > 0) {
                Text(searchMatchCount(afterCount), color = colors.ink2, style = Ember.type.meta)
            }
        }
        HorizontalDivider(
            color = colors.line,
            thickness = if (eink) 1.5.dp else 1.dp,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!loading && complete && afterCount == 0) {
            Text(stringResource(StringRes.reader_find_rest_none), color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SearchQueryField(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val colors = Ember.colors
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(query, TextRange(0, query.length))) }
    LaunchedEffect(query) { if (query != value.text) value = TextFieldValue(query, TextRange(query.length)) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(value = value, onValueChange = { value = it; onChange(it.text) }, singleLine = true,
        textStyle = Ember.type.meta.copy(color = colors.ink, fontSize = 17.sp), cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().background(colors.bg, shape).border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.chipBorder, shape).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = colors.ink2, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (value.text.isEmpty()) Text(stringResource(StringRes.reader_find_placeholder), style = Ember.type.meta.copy(fontSize = 17.sp), color = colors.ink2)
                    inner()
                }
                if (value.text.isNotEmpty()) IconButton(onClick = { value = TextFieldValue(""); onChange("") }) {
                    Icon(Icons.Default.Close, stringResource(StringRes.reader_find_clear), tint = colors.ink2)
                }
            }
        })
}

@Composable
private fun SearchMessage(title: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Ember.type.cardTitle, color = Ember.colors.ink)
        detail?.let { Text(it, style = Ember.type.meta, color = Ember.colors.ink2, modifier = Modifier.padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

@Composable
private fun SearchResultCard(hit: SearchHit, onClick: () -> Unit) {
    val result = hit.result
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(14.dp)
    val cardFill = if (eink) colors.surface else colors.ink.copy(alpha = 0.04f).compositeOver(colors.chip)
    val matchFill = if (eink) Color.Transparent else colors.accent.copy(alpha = 0.40f).compositeOver(cardFill)
    // Trim only display context. The match boundary and original navigation anchor stay intact.
    val before = result.before.orEmpty().takeLast(35)
    val after = result.after.orEmpty().take(110)
    val snippet = buildAnnotatedString {
        if (result.before.orEmpty().length > before.length) append("…")
        append(before)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.ink, background = matchFill,
            textDecoration = if (eink) TextDecoration.Underline else null)) { append(result.match.orEmpty()) }
        append(after)
        if (result.after.orEmpty().length > after.length) append("…")
    }
    val percent = result.totalProgression?.let { (it * 100).roundToInt() }
    val chapter = searchChapterLabel(hit.chapter)
    val description = if (percent != null) stringResource(StringRes.reader_find_row, chapter, percent, snippet.text)
        else stringResource(StringRes.reader_find_row_no_percent, chapter, snippet.text)
    Row(Modifier.fillMaxWidth().clip(shape).background(cardFill)
        .then(if (eink) Modifier.border(1.5.dp, colors.line, shape) else Modifier)
        .clickable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(snippet, fontFamily = literataFamily(), fontSize = 15.sp, lineHeight = 22.sp, color = colors.ink2,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        percent?.let { Text("$it%", color = colors.ink2, style = Ember.type.label, modifier = Modifier.padding(start = 10.dp)) }
    }
}

@Composable
internal fun ReaderFindBar(state: ReaderViewState, dispatch: (ReaderIntent) -> Unit, modifier: Modifier = Modifier) {
    val result = state.bookSearchResults.firstOrNull { it.index == state.selectedSearchIndex } ?: return
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val chapter = resolveSearchChapter(result, state.tableOfContents, state.bookSearchReadingOrder, state.bookSearchBoundaries)
    val hidden = state.searchBoundary != null && !state.searchAheadRevealed
    val fullySearched = state.bookSearchComplete && (state.searchBoundary == null || state.searchAheadComplete)
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.searchOrigin?.let { origin ->
            BackToOriginPill(
                origin = origin,
                onClick = { dispatch(ReaderIntent.ReturnToSearchOrigin) },
            )
        }
        if (state.showSearchContinuePrompt) {
            ContinueRestRow(
                onContinue = { dispatch(ReaderIntent.RevealSearchAhead(stepNext = true)) },
                onDismiss = { dispatch(ReaderIntent.DismissSearchContinuePrompt) },
            )
        }
        Row(Modifier.fillMaxWidth().height(60.dp).background(colors.surface, RoundedCornerShape(20.dp))
            .border(if (eink) 2.dp else 1.dp, colors.chipBorder, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val reopen = stringResource(StringRes.reader_find_reopen)
            Row(Modifier.weight(1f).clickable { dispatch(ReaderIntent.ToggleBookSearch) }.semantics { contentDescription = reopen }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = colors.ink2, modifier = Modifier.size(20.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("“${state.bookSearchQuery.trim()}”", color = colors.ink, style = Ember.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // While hidden the count is a running "so far": the rest of the book is unsearched.
                    Text(stringResource(
                        when {
                            hidden -> StringRes.reader_find_selected_so_far
                            fullySearched -> StringRes.reader_find_selected
                            else -> StringRes.reader_find_selected_partial
                        },
                        result.index + 1, state.bookSearchCount, searchChapterLabel(chapter)), color = colors.ink2, style = Ember.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { dispatch(ReaderIntent.PreviousSearchResult) }, enabled = result.index > 0,
                modifier = Modifier.size(48.dp).border(1.dp, colors.chipBorder, CircleShape)) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(StringRes.reader_find_previous), tint = colors.ink)
            }
            Spacer(Modifier.width(6.dp))
            // Next on the last read match offers to search the rest of the book instead of stopping.
            IconButton(onClick = { dispatch(ReaderIntent.NextSearchResult) },
                enabled = result.index < state.bookSearchResults.lastIndex || (hidden && state.bookSearchComplete),
                modifier = Modifier.size(48.dp).background(if (eink) Color.Black else colors.accent, CircleShape)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(StringRes.reader_find_next), tint = if (eink) Color.White else colors.onAccent)
            }
            IconButton(onClick = { dispatch(ReaderIntent.CloseBookSearch) }) { Icon(Icons.Default.Close, stringResource(StringRes.reader_find_close), tint = colors.ink2) }
        }
    }
}

/** "Continue into the rest of the book?" — offered instead of stepping past the boundary. */
@Composable
private fun ContinueRestRow(onContinue: () -> Unit, onDismiss: () -> Unit) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.chipBorder, shape)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(StringRes.reader_find_continue_rest), color = colors.ink, style = Ember.type.label,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 4.dp))
        TextButton(onClick = onDismiss) { Text(stringResource(StringRes.reader_find_not_now), color = colors.ink2) }
        TextButton(onClick = onContinue) { Text(stringResource(StringRes.reader_find_continue), color = colors.accentText, fontWeight = FontWeight.Bold) }
    }
}
