package com.retro99.reader.ui.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_detail_show_less
import resources.translations.books_detail_show_more
import resources.translations.books_search_clear
import resources.translations.reader_toc_next_chapter
import resources.translations.reader_toc_no_chapters
import resources.translations.reader_toc_no_results
import resources.translations.reader_toc_previous_chapter
import resources.translations.reader_toc_reading
import resources.translations.reader_toc_search_hint
import resources.translations.reader_toc_title

private const val COLLAPSE_THRESHOLD = 20
private const val INDENT_PER_DEPTH_DP = 12
private val ROW_HORIZONTAL_PADDING = 16.dp
private val ROW_VERTICAL_PADDING = 14.dp
private val SECTION_PADDING = 8.dp
private val PROGRESS_BAR_HEIGHT = 3.dp
private val Chevron_SIZE = 24.dp
private val ACCENT_INDICATOR_WIDTH = 24.dp
private val ACCENT_INDICATOR_HEIGHT = 2.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableOfContentsSheet(
    tableOfContents: List<TocItemUiModel>,
    currentChapterHref: String?,
    bookProgression: Double?,
    onChapterClick: (href: String) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        TableOfContentsContent(
            tableOfContents = tableOfContents,
            currentChapterHref = currentChapterHref,
            bookProgression = bookProgression,
            onChapterClick = onChapterClick,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
        )
    }
}

@Composable
private fun TableOfContentsContent(
    tableOfContents: List<TocItemUiModel>,
    currentChapterHref: String?,
    bookProgression: Double?,
    onChapterClick: (href: String) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val listState = rememberLazyListState()
    val searchFieldState = remember { TextFieldState("") }

    val tocNodes = remember(tableOfContents) { tableOfContents.toTocNodes() }
    val currentChapterFlatIndex = remember(tableOfContents, currentChapterHref) {
        if (currentChapterHref == null) -1
        else tableOfContents.indexOfFirst { item -> item.href == currentChapterHref }
    }

    val expandedStates = remember { mutableStateMapOf<Int, Boolean>() }

    LaunchedEffect(tocNodes, currentChapterFlatIndex) {
        if (tocNodes.isNotEmpty()) {
            if (tableOfContents.size <= COLLAPSE_THRESHOLD) {
                expandAll(tocNodes, expandedStates)
            } else if (currentChapterFlatIndex >= 0) {
                expandedStates.clear()
                val ancestors = findAncestorFlatIndices(tocNodes, currentChapterFlatIndex)
                ancestors.forEach { index -> expandedStates[index] = true }
            }
        }
    }

    val searchQuery = searchFieldState.text.toString()

    val visibleEntries by remember(tocNodes) {
        derivedStateOf {
            val query = searchFieldState.text.toString()
            if (query.isNotEmpty()) {
                filterFlatEntries(tableOfContents, query)
            } else {
                flattenTocNodes(tocNodes, expandedStates)
            }
        }
    }

    val currentChapterVisibleIndex = if (currentChapterFlatIndex < 0) -1
    else visibleEntries.indexOfFirst { entry -> entry.node.flatIndex == currentChapterFlatIndex }

    LaunchedEffect(currentChapterVisibleIndex, searchQuery) {
        if (searchQuery.isEmpty() && currentChapterVisibleIndex >= 0) {
            listState.animateScrollToItem(currentChapterVisibleIndex)
        }
    }

    val hasPrevious = currentChapterFlatIndex > 0
    val hasNext = currentChapterFlatIndex in 0..(tableOfContents.size - 2)

    Column(modifier = Modifier.fillMaxWidth()) {
        TocHeader(bookProgression = bookProgression)

        TocSearchBar(
            searchFieldState = searchFieldState,
            modifier = Modifier.padding(
                horizontal = ROW_HORIZONTAL_PADDING,
                vertical = SECTION_PADDING,
            ),
        )

        TocChapterNavigation(
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            modifier = Modifier.padding(
                horizontal = ROW_HORIZONTAL_PADDING,
                vertical = SECTION_PADDING,
            ),
        )

        HorizontalDivider()

        if (tableOfContents.isEmpty()) {
            TocEmptyState()
        } else if (searchQuery.isNotEmpty() && visibleEntries.isEmpty()) {
            TocNoSearchResults()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(
                    items = visibleEntries,
                    key = { entry -> entry.node.flatIndex },
                ) { entry ->
                    val isCurrentChapter = entry.node.item.href == currentChapterHref
                    val isRead = entry.node.flatIndex < currentChapterFlatIndex
                    TocItemRow(
                        entry = entry,
                        isCurrentChapter = isCurrentChapter,
                        isRead = isRead,
                        onClick = { onChapterClick(entry.node.item.href) },
                        onToggleExpand = {
                            val currentState = expandedStates[entry.node.flatIndex] ?: false
                            expandedStates[entry.node.flatIndex] = !currentState
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TocHeader(
    bookProgression: Double?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(StringRes.reader_toc_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(
                horizontal = ROW_HORIZONTAL_PADDING,
                vertical = SECTION_PADDING,
            ),
        )
        if (bookProgression != null) {
            LinearProgressIndicator(
                progress = { bookProgression.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PROGRESS_BAR_HEIGHT)
                    .padding(horizontal = ROW_HORIZONTAL_PADDING),
            )
        }
    }
}

@Composable
private fun TocSearchBar(
    searchFieldState: TextFieldState,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        state = searchFieldState,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(StringRes.reader_toc_search_hint)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(StringRes.reader_toc_search_hint),
            )
        },
        trailingIcon = {
            if (searchFieldState.text.isNotEmpty()) {
                IconButton(onClick = { searchFieldState.clearText() }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(StringRes.books_search_clear),
                    )
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        lineLimits = TextFieldLineLimits.SingleLine,
    )
}

@Composable
private fun TocChapterNavigation(
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!hasPrevious && !hasNext) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onPreviousChapter,
            enabled = hasPrevious,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(StringRes.reader_toc_previous_chapter),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onNextChapter,
            enabled = hasNext,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(StringRes.reader_toc_next_chapter),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TocEmptyState(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(StringRes.reader_toc_no_chapters),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(ROW_HORIZONTAL_PADDING),
    )
}

@Composable
private fun TocNoSearchResults(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(StringRes.reader_toc_no_results),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(ROW_HORIZONTAL_PADDING),
    )
}

@Composable
private fun TocItemRow(
    entry: FlatTocEntry,
    isCurrentChapter: Boolean,
    isRead: Boolean,
    onClick: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indentStart = ROW_HORIZONTAL_PADDING + (entry.depth * INDENT_PER_DEPTH_DP).dp

    val animatedTextColor by animateColorAsState(
        targetValue = when {
            isCurrentChapter -> MaterialTheme.colorScheme.primary
            isRead -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        label = "tocText",
    )

    val chevronRotation by animateFloatAsState(
        targetValue = if (entry.isExpanded) 0f else -90f,
        label = "tocChevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (entry.node.hasChildren && !isCurrentChapter) {
                    onToggleExpand()
                } else {
                    onClick()
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indentStart, end = ROW_HORIZONTAL_PADDING)
                .padding(vertical = ROW_VERTICAL_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.node.hasChildren) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(Chevron_SIZE),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = if (entry.isExpanded) {
                            stringResource(StringRes.books_detail_show_less)
                        } else {
                            stringResource(StringRes.books_detail_show_more)
                        },
                        modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(Chevron_SIZE))
            }

            Text(
                text = entry.node.item.title,
                style = if (entry.depth == 0) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal,
                color = animatedTextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (isCurrentChapter) {
            Box(
                modifier = Modifier
                    .padding(start = indentStart + Chevron_SIZE, end = ROW_HORIZONTAL_PADDING)
                    .padding(bottom = 6.dp)
                    .width(ACCENT_INDICATOR_WIDTH)
                    .height(ACCENT_INDICATOR_HEIGHT)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun expandAll(
    nodes: List<TocNode>,
    expandedStates: MutableMap<Int, Boolean>,
) {
    for (node in nodes) {
        if (node.hasChildren) {
            expandedStates[node.flatIndex] = true
            expandAll(node.children, expandedStates)
        }
    }
}

private fun filterFlatEntries(
    tableOfContents: List<TocItemUiModel>,
    query: String,
): List<FlatTocEntry> {
    val lowerQuery = query.lowercase()
    return tableOfContents
        .filter { it.title.lowercase().contains(lowerQuery) }
        .map { item ->
            val originalIndex = tableOfContents.indexOf(item)
            FlatTocEntry(
                node = TocNode(item, originalIndex),
                depth = 0,
                isExpanded = false,
            )
        }
}
