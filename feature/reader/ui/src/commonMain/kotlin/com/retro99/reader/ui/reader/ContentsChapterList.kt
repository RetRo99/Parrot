package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.translations.PluralRes
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_contents_about_min_left
import resources.translations.reader_contents_chapter_page_of
import resources.translations.reader_contents_chapter_quantity
import resources.translations.reader_contents_collapsed
import resources.translations.reader_contents_current_row
import resources.translations.reader_contents_current_row_no_pages
import resources.translations.reader_contents_expanded
import resources.translations.reader_contents_group_row
import resources.translations.reader_contents_group_row_here
import resources.translations.reader_contents_go_to
import resources.translations.reader_contents_here_suffix
import resources.translations.reader_contents_you_are_here
import resources.translations.reader_toc_no_chapters
import resources.translations.reader_toc_no_results

/** Rows scrolled above the current card on open, putting it about a third from the top. */
private const val CURRENT_SCROLL_OFFSET = 4
private const val DEPTH_INDENT_DP = 20
private const val CHAPTER_INDENT_DP = 38
private const val FLAT_INDENT_DP = 12

/** Meta of a group row: "28 chapters", or a bare count for small groups like "3". */
@Composable
internal fun groupChapterCount(count: Int): String =
    if (count <= SMALL_GROUP_MAX) count.toString()
    else pluralStringResource(PluralRes.reader_contents_chapter_quantity, count, count)

@Composable
internal fun ContentsChaptersTab(
    toc: List<TocItemUiModel>,
    expandedGroups: Set<Int>,
    currentFlatIndex: Int?,
    chapterInfo: ChapterInfo?,
    chapterReadingTimeInfo: ChapterReadingTimeInfo?,
    resourceProgression: Double?,
    isEink: Boolean,
    onToggleGroup: (Int) -> Unit,
    onNavigateToHref: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    if (toc.isEmpty()) {
        Text(
            stringResource(StringRes.reader_toc_no_chapters),
            color = colors.ink2,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    val rows = remember(toc, expandedGroups, currentFlatIndex) {
        buildContentsRows(toc, expandedGroups, currentFlatIndex)
    }
    val listState = rememberLazyListState()
    val currentIndex = rows.indexOfFirst { it is ContentsChapterRow && it.isCurrent }
    // Scroll to the current card on open (and when the tree comes back from filtering),
    // but not when the reader merely folds or unfolds a group.
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (!initialScrollDone && currentIndex >= 0) {
            initialScrollDone = true
            listState.scrollToItem((currentIndex - CURRENT_SCROLL_OFFSET).coerceAtLeast(0))
        }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ContentsGroupRow -> TocGroupRow(
                    row = row,
                    isEink = isEink,
                    onToggle = { onToggleGroup(row.flatIndex) },
                    onNavigate = { onNavigateToHref(row.href) },
                )

                is ContentsChapterRow -> if (row.isCurrent) {
                    CurrentChapterCard(
                        title = row.item.title,
                        depth = row.depth,
                        chapterInfo = chapterInfo,
                        chapterReadingTimeInfo = chapterReadingTimeInfo,
                        resourceProgression = resourceProgression,
                        isEink = isEink,
                        onClick = { onNavigateToHref(row.item.href) },
                    )
                } else {
                    TocChapterRow(
                        row = row,
                        onClick = { onNavigateToHref(row.item.href) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TocGroupRow(
    row: ContentsGroupRow,
    isEink: Boolean,
    onToggle: () -> Unit,
    onNavigate: () -> Unit,
) {
    val colors = Ember.colors
    val stateLabel = stringResource(
        if (row.isExpanded) StringRes.reader_contents_expanded else StringRes.reader_contents_collapsed,
    )
    val hereLabel = if (row.hidesCurrent) stringResource(StringRes.reader_contents_here_suffix) else null
    val navigateLabel = stringResource(StringRes.reader_contents_go_to, row.title)
    val count = groupChapterCount(row.chapterCount)
    val description = if (hereLabel != null) {
        stringResource(StringRes.reader_contents_group_row_here, row.title, count, stateLabel, hereLabel)
    } else {
        stringResource(StringRes.reader_contents_group_row, row.title, count, stateLabel)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction(navigateLabel) { onNavigate(); true })
            }
            .combinedClickable(onClick = onToggle, onLongClick = onNavigate)
            .padding(start = (row.depth * DEPTH_INDENT_DP).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (row.isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (row.hidesCurrent && !isEink) colors.accentText else colors.ink,
            modifier = Modifier.size(24.dp).padding(top = 1.dp),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
            Text(
                text = row.title.uppercase(),
                // E-ink accent is just black there: mark the row with an underline, as search
                // highlights do, instead of a colour tint.
                color = if (row.hidesCurrent && !isEink) colors.accentText else colors.ink,
                textDecoration = if (row.hidesCurrent && isEink) TextDecoration.Underline else null,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                // Long part names get the full row width: two lines before any ellipsis.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(count, hereLabel).joinToString(" · "),
                color = colors.ink2,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TocChapterRow(
    row: ContentsChapterRow,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(start = chapterIndent(row.depth), end = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = row.item.title,
            color = colors.ink,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun chapterIndent(depth: Int) =
    (if (depth <= 0) FLAT_INDENT_DP else CHAPTER_INDENT_DP + (depth - 1) * DEPTH_INDENT_DP).dp

/**
 * The one honest "you're here": this chapter's card with what is actually known about it —
 * rendered page and reading time only when available, and progress within the chapter
 * (never labelled as a book percentage).
 */
@Composable
private fun CurrentChapterCard(
    title: String,
    depth: Int,
    chapterInfo: ChapterInfo?,
    chapterReadingTimeInfo: ChapterReadingTimeInfo?,
    resourceProgression: Double?,
    isEink: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(14.dp)
    val pageLine = if (chapterInfo != null) {
        stringResource(
            StringRes.reader_contents_chapter_page_of,
            chapterInfo.currentPage,
            chapterInfo.totalPages,
        )
    } else {
        null
    }
    val timeLine = chapterReadingTimeInfo?.let {
        stringResource(StringRes.reader_contents_about_min_left, it.remainingMinutes)
    }
    val description = if (chapterInfo != null) {
        stringResource(StringRes.reader_contents_current_row, title, chapterInfo.currentPage, chapterInfo.totalPages)
    } else {
        stringResource(StringRes.reader_contents_current_row_no_pages, title)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (isEink) colors.surface else colors.navActive.copy(alpha = 0.5f))
                .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .clickable(onClick = onClick)
                .padding(start = chapterIndent(depth), end = 14.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(StringRes.reader_contents_you_are_here),
                    color = colors.accentText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (pageLine != null || timeLine != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pageLine != null) {
                        Text(
                            text = pageLine,
                            color = colors.ink2,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (timeLine != null) {
                        Text(
                            text = timeLine,
                            color = colors.ink2,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (resourceProgression != null) {
                Spacer(Modifier.height(8.dp))
                ThinProgress(resourceProgression, isEink)
            }
        }
    }
}

/** Thin read-only progress line for progress within the current chapter. */
@Composable
private fun ThinProgress(progress: Double, isEink: Boolean, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(2.dp)
    val fraction = progress.coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(shape)
            .background(colors.track)
            .then(if (isEink) Modifier.border(1.dp, colors.line, shape) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(colors.accent))
    }
}

/**
 * "Find a chapter" results: each match keeps its group header, and the matched text is
 * highlighted — filled on regular displays, underlined on e-ink.
 */
@Composable
internal fun ContentsSearchResults(
    toc: List<TocItemUiModel>,
    matches: List<TocMatch>,
    isEink: Boolean,
    onNavigateToHref: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    if (matches.isEmpty()) {
        Text(
            stringResource(StringRes.reader_toc_no_results),
            color = colors.ink2,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    data class SearchRow(val group: TocItemUiModel?, val match: TocMatch)
    val rows = remember(matches) {
        buildList {
            var lastGroup: Int? = null
            for (match in matches) {
                if (match.parentFlatIndex != lastGroup) add(SearchRow(match.parentFlatIndex?.let { toc[it] }, match))
                else add(SearchRow(null, match))
                lastGroup = match.parentFlatIndex
            }
        }
    }
    LazyColumn(state = rememberLazyListState(), modifier = modifier.fillMaxWidth()) {
        items(rows, key = { "match-${it.match.flatIndex}" }) { row ->
            row.group?.let { group -> SearchGroupHeader(group, match = row.match) }
            val depth = if (row.match.parentFlatIndex != null) 1 else 0
            val matchRow = ContentsChapterRow(
                flatIndex = row.match.flatIndex,
                item = row.match.item,
                depth = depth,
                isCurrent = false,
                matchRange = row.match.matchRange,
            )
            HighlightedChapterRow(
                row = matchRow,
                isEink = isEink,
                onClick = { onNavigateToHref(row.match.item.href) },
            )
        }
    }
}

@Composable
private fun SearchGroupHeader(group: TocItemUiModel, match: TocMatch) {
    val colors = Ember.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(24.dp).padding(top = 1.dp),
        )
        Text(
            text = group.title.uppercase(),
            color = colors.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
        )
    }
}

@Composable
private fun HighlightedChapterRow(
    row: ContentsChapterRow,
    isEink: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    val range = row.matchRange
    val title = row.item.title
    val text = if (range == null || range.first < 0 || range.last >= title.length) {
        AnnotatedString(title)
    } else {
        buildAnnotatedString {
            append(title.substring(0, range.first))
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    background = if (isEink) androidx.compose.ui.graphics.Color.Transparent
                    else colors.accent.copy(alpha = 0.35f),
                    textDecoration = if (isEink) TextDecoration.Underline else null,
                ),
            ) {
                append(title.substring(range.first, range.last + 1))
            }
            append(title.substring(range.last + 1))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(start = chapterIndent(row.depth), end = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = colors.ink,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
