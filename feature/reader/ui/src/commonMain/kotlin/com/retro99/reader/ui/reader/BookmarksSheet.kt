package com.retro99.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.nowMillis
import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.RelativeTime
import com.retro99.reader.ui.model.relativeTimeFromIso
import com.retro99.translations.StringRes
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import org.jetbrains.compose.resources.stringResource
import resources.translations.action_delete
import resources.translations.action_edit
import resources.translations.reader_bookmark_add
import resources.translations.reader_bookmark_add_current_page
import resources.translations.reader_bookmark_default_title
import resources.translations.reader_bookmark_days_ago
import resources.translations.reader_bookmark_empty_hint
import resources.translations.reader_bookmark_hours_ago
import resources.translations.reader_bookmark_just_now
import resources.translations.reader_bookmark_minutes_ago
import resources.translations.reader_bookmark_rename
import resources.translations.reader_bookmark_rename_cancel
import resources.translations.reader_bookmark_rename_confirm
import resources.translations.reader_bookmark_rename_label
import resources.translations.reader_bookmarks_empty
import resources.translations.reader_bookmarks_title
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkUiModel>,
    onAddBookmarkClick: () -> Unit,
    onBookmarkClick: (BookmarkUiModel) -> Unit,
    onBookmarkDelete: (String) -> Unit,
    onBookmarkRename: (String, String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        BookmarksSheetContent(
            bookmarks = bookmarks,
            onAddBookmarkClick = onAddBookmarkClick,
            onBookmarkClick = onBookmarkClick,
            onBookmarkDelete = onBookmarkDelete,
            onBookmarkRename = onBookmarkRename,
            onReorder = onReorder,
        )
    }
}

@Composable
private fun BookmarksSheetContent(
    bookmarks: List<BookmarkUiModel>,
    onAddBookmarkClick: () -> Unit,
    onBookmarkClick: (BookmarkUiModel) -> Unit,
    onBookmarkDelete: (String) -> Unit,
    onBookmarkRename: (String, String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var renamingBookmark by remember { mutableStateOf<BookmarkUiModel?>(null) }
    var localBookmarks by remember { mutableStateOf(bookmarks) }
    var dragActive by remember { mutableStateOf(false) }
    var pendingDbSync by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(bookmarks, pendingDbSync) {
        if (pendingDbSync != null &&
            bookmarks.map { it.id } == pendingDbSync
        ) {
            pendingDbSync = null
            dragActive = false
        }
    }

    if (!dragActive && pendingDbSync == null && localBookmarks != bookmarks) {
        localBookmarks = bookmarks
    }

    renamingBookmark?.let { bookmark ->
        BookmarkRenameDialog(
            bookmark = bookmark,
            onConfirm = { newTitle ->
                onBookmarkRename(bookmark.id, newTitle)
                renamingBookmark = null
            },
            onDismiss = { renamingBookmark = null },
        )
    }

    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localBookmarks = localBookmarks.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(StringRes.reader_bookmarks_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        Button(
            onClick = onAddBookmarkClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(StringRes.reader_bookmark_add),
            )
            Text(
                text = stringResource(StringRes.reader_bookmark_add_current_page),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (localBookmarks.isEmpty()) {
            Text(
                text = stringResource(StringRes.reader_bookmarks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            Text(
                text = stringResource(StringRes.reader_bookmark_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(
                    items = localBookmarks,
                    key = { it.id },
                ) { bookmark ->
                    ReorderableItem(
                        state = reorderableState,
                        key = bookmark.id,
                    ) { isDraggingItem ->
                        val dragHandleModifier = Modifier.draggableHandle(
                            onDragStarted = { dragActive = true },
                            onDragStopped = {
                                val newOrder = localBookmarks.map { it.id }
                                pendingDbSync = newOrder
                                onReorder(newOrder)
                            },
                        )
                        BookmarkRow(
                            bookmark = bookmark,
                            isDragging = isDraggingItem,
                            dragHandleModifier = dragHandleModifier,
                            onClick = { onBookmarkClick(bookmark) },
                            onDelete = { onBookmarkDelete(bookmark.id) },
                            onRename = { renamingBookmark = bookmark },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: BookmarkUiModel,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressText = bookmark.totalProgression
        ?.let { progress -> "${bookPercent(progress)}%" }
        ?: ""

    val defaultTitle = stringResource(StringRes.reader_bookmark_default_title)

    val relativeTimeText = remember(bookmark.createdAt) {
        relativeTimeFromIso(bookmark.createdAt, nowMillis())
    }
    val timeAgoText = when (val rt = relativeTimeText) {
        RelativeTime.JustNow -> stringResource(StringRes.reader_bookmark_just_now)
        is RelativeTime.MinutesAgo -> stringResource(
            StringRes.reader_bookmark_minutes_ago,
            rt.minutes,
        )
        is RelativeTime.HoursAgo -> stringResource(
            StringRes.reader_bookmark_hours_ago,
            rt.hours,
        )
        is RelativeTime.DaysAgo -> stringResource(
            StringRes.reader_bookmark_days_ago,
            rt.days,
        )
    }

    val elevation = if (isDragging) 8.dp else 0.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = elevation,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = stringResource(StringRes.reader_bookmark_default_title),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.locatorTitle ?: defaultTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progressText.isNotEmpty()) {
                    Text(
                        text = "$progressText  •  $timeAgoText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = timeAgoText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(StringRes.reader_bookmark_rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(StringRes.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = {},
                modifier = dragHandleModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(StringRes.action_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun BookmarkRenameDialog(
    bookmark: BookmarkUiModel,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultTitle = stringResource(StringRes.reader_bookmark_default_title)
    var textFieldValue by remember {
        mutableStateOf(bookmark.locatorTitle ?: defaultTitle)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(StringRes.reader_bookmark_rename))
        },
        text = {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                label = {
                    Text(text = stringResource(StringRes.reader_bookmark_rename_label))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(textFieldValue) },
            ) {
                Text(text = stringResource(StringRes.reader_bookmark_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(text = stringResource(StringRes.reader_bookmark_rename_cancel))
            }
        },
    )
}
