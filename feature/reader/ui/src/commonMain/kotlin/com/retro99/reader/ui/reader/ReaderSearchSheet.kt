package com.retro99.reader.ui.reader

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
    data object Ahead : SearchRow { override val key = "ahead" }
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
    val referencePosition = state.bookSearchReferencePosition ?: state.searchOrigin ?: state.currentPosition
    val split = searchPositionSplit(hits.map { it.result }, referencePosition, state.bookSearchReadingOrder)
    var showAhead by remember(state.bookSearchQuery) { mutableStateOf(state.selectedSearchIndex?.let { selected ->
        split != null && hits.indexOfFirst { it.result.index == selected } >= split
    } == true) }
    var nearestRequested by remember(state.bookSearchQuery) { mutableStateOf(false) }
    // A closed ahead section must not disclose even whether later matches exist.
    // If the position cannot be compared, keep all snippets behind explicit reveal.
    val visibleHits = if (showAhead) hits else hits.take(split ?: 0)
    val counts = if (showAhead) state.bookSearchChapterCounts.ifEmpty { hits.groupingBy { it.chapter.key }.eachCount() }
        else visibleHits.groupingBy { it.chapter.key }.eachCount()
    val rows = buildList {
        var previous: String? = null
        hits.forEachIndexed { index, hit ->
            if (index >= (split ?: 0) && !showAhead) return@forEachIndexed
            if (hit.chapter.key != previous) add(SearchRow.Header(hit.chapter, counts.getValue(hit.chapter.key), hit.result.index))
            add(SearchRow.Hit(hit))
            previous = hit.chapter.key
        }
        add(SearchRow.Ahead)
    }
    val listState = rememberLazyListState()
    var scrolled by remember(state.bookSearchQuery) { mutableStateOf(false) }
    LaunchedEffect(rows.size, split, state.bookSearchComplete, nearestRequested) {
        if (nearestRequested && split != null && split < hits.size) {
            val target = rows.indexOfFirst { it is SearchRow.Hit && it.hit.result.index == hits[split].result.index }
            if (target >= 0) { listState.scrollToItem(target); nearestRequested = false; scrolled = true }
            return@LaunchedEffect
        }
        if (!scrolled && hits.isNotEmpty()) {
            val target = state.selectedSearchIndex?.let { selected -> rows.indexOfFirst { it is SearchRow.Hit && it.hit.result.index == selected } }
                ?: rows.indexOfFirst { it == SearchRow.Ahead }
            if (target >= 0 && (state.selectedSearchIndex != null || (split != null && split < hits.size) || state.bookSearchComplete)) {
                listState.scrollToItem(target); scrolled = true
            }
        }
    }
    val summary = when {
        !showAhead -> stringResource(StringRes.reader_find_earlier_summary, searchMatchCount(visibleHits.size))
        state.isBookSearchLoading -> stringResource(StringRes.reader_find_running, state.bookSearchCount)
        state.bookSearchComplete -> stringResource(StringRes.reader_find_summary, searchMatchCount(state.bookSearchCount),
            pluralStringResource(PluralRes.reader_find_chapter_quantity, counts.size, counts.size))
        else -> stringResource(StringRes.reader_find_partial, searchMatchCount(state.bookSearchCount),
            pluralStringResource(PluralRes.reader_find_chapter_quantity, counts.size, counts.size))
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
                                .semantics { if (state.bookSearchComplete) liveRegion = LiveRegionMode.Polite })
                            if (showAhead && hits.isNotEmpty() && split != null && split < hits.size) {
                                OutlinedButton(onClick = {
                                    showAhead = true
                                    nearestRequested = true
                                }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), border = androidx.compose.foundation.BorderStroke(1.dp, colors.chipBorder)) {
                                    Text(stringResource(StringRes.reader_find_nearest), color = colors.accentText, style = Ember.type.label)
                                }
                            }
                        }
                        if (referencePosition != null) {
                            val percent = referencePosition?.totalProgression
                            Text(if (percent != null) stringResource(StringRes.reader_find_position, (percent * 100).roundToInt())
                                else stringResource(StringRes.reader_find_position_no_percent), color = colors.ink2, style = Ember.type.meta)
                            val capped = state.bookSearchCount > SEARCH_RESULT_LIMIT
                            val direction = if (!showAhead) null else when (split) {
                                hits.size -> if (capped) StringRes.reader_find_before_capped else StringRes.reader_find_before
                                0 -> if (capped) StringRes.reader_find_after_capped else StringRes.reader_find_after
                                else -> null
                            }
                            direction?.let { Text(stringResource(it), color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp)) }
                        }
                        if (state.bookSearchFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(StringRes.reader_find_failed), color = colors.ink2, modifier = Modifier.weight(1f))
                            TextButton(onClick = { dispatch(ReaderIntent.SubmitBookSearch) }) { Text(stringResource(StringRes.reader_find_retry)) }
                        }
                        if (showAhead && state.bookSearchCount > SEARCH_RESULT_LIMIT) Text(stringResource(StringRes.reader_find_cap, SEARCH_RESULT_LIMIT), color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(bottom = 8.dp))
                        if (showAhead && state.bookSearchComplete && hits.isEmpty()) {
                            SearchMessage(stringResource(StringRes.reader_find_empty, state.bookSearchQuery.trim()), stringResource(StringRes.reader_find_empty_hint))
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(rows, key = { it.key }) { row -> when (row) {
                                    is SearchRow.Header -> Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text((row.chapter.number?.let { "$it · " } ?: "") + searchChapterLabel(row.chapter), color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Text(searchMatchCount(row.count), color = colors.ink2, style = Ember.type.meta)
                                    }
                                    is SearchRow.Hit -> SearchResultCard(row.hit) { dispatch(ReaderIntent.GoToSearchResult(row.hit.result)) }
                                    SearchRow.Ahead -> {
                                        OutlinedButton(onClick = { showAhead = !showAhead }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(if (eink) 1.5.dp else 1.dp, colors.chipBorder),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                                Text(stringResource(StringRes.reader_find_ahead), color = colors.ink, style = Ember.type.label)
                                                Text(stringResource(if (showAhead) StringRes.reader_find_hide_ahead else StringRes.reader_find_spoilers),
                                                    color = colors.ink2, style = Ember.type.meta, modifier = Modifier.padding(top = 3.dp))
                                            }
                                            Icon(if (showAhead) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                stringResource(if (showAhead) StringRes.reader_find_hide_ahead else StringRes.reader_find_show_ahead), tint = colors.accentText)
                                        }
                                    }
                                } }
                            }
                        }
                    }
                }
            }
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
    val chapter = hit.chapter.number?.let { stringResource(StringRes.reader_find_chapter, it) } ?: searchChapterLabel(hit.chapter)
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
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.searchOrigin?.let { origin ->
            OutlinedButton(onClick = { dispatch(ReaderIntent.ReturnToSearchOrigin) }, shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surface), border = androidx.compose.foundation.BorderStroke(if (eink) 2.dp else 1.dp, colors.chipBorder)) {
                Icon(Icons.Default.Undo, null, tint = colors.ink, modifier = Modifier.size(18.dp))
                Text(origin.totalProgression?.let { stringResource(StringRes.reader_find_back, (it * 100).roundToInt()) }
                    ?: stringResource(StringRes.reader_find_back_no_percent), color = colors.ink, style = Ember.type.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().height(60.dp).background(colors.surface, RoundedCornerShape(20.dp))
            .border(if (eink) 2.dp else 1.dp, colors.chipBorder, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val reopen = stringResource(StringRes.reader_find_reopen)
            Row(Modifier.weight(1f).clickable { dispatch(ReaderIntent.ToggleBookSearch) }.semantics { contentDescription = reopen }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = colors.ink2, modifier = Modifier.size(20.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("“${state.bookSearchQuery.trim()}”", color = colors.ink, style = Ember.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (state.bookSearchComplete) StringRes.reader_find_selected else StringRes.reader_find_selected_partial,
                        result.index + 1, state.bookSearchCount, searchChapterLabel(chapter)), color = colors.ink2, style = Ember.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { dispatch(ReaderIntent.PreviousSearchResult) }, enabled = result.index > 0,
                modifier = Modifier.size(48.dp).border(1.dp, colors.chipBorder, CircleShape)) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(StringRes.reader_find_previous), tint = colors.ink)
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { dispatch(ReaderIntent.NextSearchResult) }, enabled = result.index < state.bookSearchResults.lastIndex,
                modifier = Modifier.size(48.dp).background(if (eink) Color.Black else colors.accent, CircleShape)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(StringRes.reader_find_next), tint = if (eink) Color.White else colors.onAccent)
            }
            IconButton(onClick = { dispatch(ReaderIntent.CloseBookSearch) }) { Icon(Icons.Default.Close, stringResource(StringRes.reader_find_close), tint = colors.ink2) }
        }
    }
}
