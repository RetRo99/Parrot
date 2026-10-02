package com.retro99.reader.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.nowMillis
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.RelativeTime
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.reader.ui.model.relativeTimeFromIso
import com.retro99.translations.StringRes
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import resources.translations.action_edit
import resources.translations.reader_bookmark_add_current_page
import resources.translations.reader_bookmark_default_title
import resources.translations.reader_bookmark_days_ago
import resources.translations.reader_bookmark_hours_ago
import resources.translations.reader_bookmark_just_now
import resources.translations.reader_bookmark_minutes_ago
import resources.translations.reader_bookmark_rename
import resources.translations.reader_bookmark_rename_cancel
import resources.translations.reader_bookmark_rename_confirm
import resources.translations.reader_bookmark_rename_label
import resources.translations.reader_bookmarks_empty
import resources.translations.reader_contents_bookmark_here
import resources.translations.reader_contents_delete_bookmark
import resources.translations.reader_contents_done
import resources.translations.reader_contents_empty_body
import resources.translations.reader_contents_in_book_order
import resources.translations.reader_contents_rename_bookmark
import resources.translations.reader_contents_saved_relative
import resources.translations.reader_contents_saved_yesterday

/**
 * Bookmarks tab: always in book order. The title is the reader's own name for the place
 * when they gave it one, otherwise the chapter title resolved from the TOC — never a
 * locator position printed as "Chapter N · Page P".
 */
@Composable
internal fun ContentsBookmarksTab(
    bookmarks: List<BookmarkUiModel>,
    toc: List<TocItemUiModel>,
    readingOrder: List<String>,
    currentPosition: PositionUiModel?,
    isEink: Boolean,
    onBookmarkClick: (BookmarkUiModel) -> Unit,
    onAddBookmark: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    var editing by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BookmarkUiModel?>(null) }

    renameTarget?.let { bookmark ->
        BookmarkRenamePrompt(
            bookmark = bookmark,
            onConfirm = { newTitle -> onRename(bookmark.id, newTitle); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    val ordered = remember(bookmarks) {
        bookmarks.sortedWith(compareBy({ it.totalProgression ?: Double.MAX_VALUE }, { it.createdAt }))
    }
    val isBookmarkedHere = currentPosition != null &&
        bookmarks.any { bookmarkMatchesPosition(it, currentPosition) }

    if (ordered.isEmpty()) {
        EmptyBookmarks(isEink = isEink, onAddBookmark = onAddBookmark, modifier = modifier)
        return
    }

    val locations = remember(ordered, toc, readingOrder) {
        ordered.associate { bookmark ->
            bookmark.id to findTocLocation(toc, bookmark.locatorHref, bookmark.progression, readingOrder)
        }
    }
    val parents = remember(toc) { tocParentIndices(toc) }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(StringRes.reader_contents_in_book_order),
                color = colors.ink2,
                style = Ember.type.label,
                modifier = Modifier.weight(1f),
            )
            EditPill(editing = editing, onToggle = { editing = !editing })
        }
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            items(ordered, key = { it.id }) { bookmark ->
                val location = locations[bookmark.id]
                val chapterTitle = location?.item?.title
                val groupTitle = location?.flatIndex?.let { parents[it] }?.let { toc[it].title }
                BookmarkOverlayRow(
                    bookmark = bookmark,
                    chapterTitle = chapterTitle,
                    groupTitle = groupTitle,
                    editing = editing,
                    isEink = isEink,
                    onClick = { onBookmarkClick(bookmark) },
                    onDelete = { onDelete(bookmark.id) },
                    onRename = { renameTarget = bookmark },
                )
            }
        }
        AddBookmarkButton(
            enabled = !isBookmarkedHere,
            isEink = isEink,
            onClick = onAddBookmark,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun EditPill(editing: Boolean, onToggle: () -> Unit) {
    val colors = Ember.colors
    val label = stringResource(if (editing) StringRes.reader_contents_done else StringRes.action_edit)
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (editing) colors.accent else colors.surface)
            .then(if (editing) Modifier else Modifier.border(1.dp, colors.chipBorder, CircleShape))
            .semantics { role = Role.Button; contentDescription = label }
            .clickable(onClick = onToggle)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (editing) colors.onAccent else colors.ink,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun BookmarkOverlayRow(
    bookmark: BookmarkUiModel,
    chapterTitle: String?,
    groupTitle: String?,
    editing: Boolean,
    isEink: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val colors = Ember.colors
    val customName = bookmark.locatorTitle?.trim().orEmpty()
        .takeIf { it.isNotEmpty() && it != chapterTitle }
    val title = customName ?: chapterTitle ?: bookmark.locatorTitle?.trim().orEmpty()
        .ifEmpty { stringResource(StringRes.reader_bookmark_default_title) }
    val context = if (customName != null) chapterTitle else groupTitle
    val saved = savedTimeText(bookmark.createdAt)
    val percent = bookmark.totalProgression?.let { "${bookPercent(it)}%" }
    val renameLabel = stringResource(StringRes.reader_contents_rename_bookmark, title)
    val deleteLabel = stringResource(StringRes.reader_contents_delete_bookmark, title)

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clickable(enabled = !editing, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "$title. ${context.orEmpty()} $saved" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(
                    text = title,
                    color = colors.ink,
                    style = Ember.type.bookTitle.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondLine = listOfNotNull(context, saved).joinToString(" · ")
                Text(
                    text = secondLine,
                    color = colors.ink2,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editing) {
                OutlinedCircleButton(description = renameLabel, tint = colors.ink, onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                OutlinedCircleButton(
                    description = deleteLabel,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (percent != null) {
                Text(text = percent, color = colors.ink2, style = Ember.type.label)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
private fun OutlinedCircleButton(
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Ember.colors
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .border(1.dp, colors.chipBorder, CircleShape)
            .semantics { contentDescription = description },
    ) {
        content()
    }
}

/** "+ Bookmark this page", or the disabled notice when this place is already saved. */
@Composable
private fun AddBookmarkButton(
    enabled: Boolean,
    isEink: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val label = stringResource(
        if (enabled) StringRes.reader_bookmark_add_current_page else StringRes.reader_contents_bookmark_here,
    )
    val shape = RoundedCornerShape(14.dp)
    val strokeColor = if (enabled) colors.accent else colors.chipBorder
    val textColor = if (enabled) colors.accentText else colors.ink2
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .drawBehind {
                if (isEink) return@drawBehind
                val stroke = 2.dp.toPx()
                val radius = 14.dp.toPx()
                drawRoundRect(
                    color = strokeColor,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                    ),
                )
            }
            .then(if (isEink) Modifier.border(if (enabled) 2.dp else 1.dp, strokeColor, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (enabled) {
            Icon(Icons.Default.Add, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun EmptyBookmarks(
    isEink: Boolean,
    onAddBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Icon(
            imageVector = Icons.Default.BookmarkBorder,
            contentDescription = null,
            tint = colors.ink2,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(StringRes.reader_bookmarks_empty),
            color = colors.ink,
            style = Ember.type.cardTitle,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(StringRes.reader_contents_empty_body),
            color = colors.ink2,
            style = Ember.type.meta,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAddBookmark,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isEink) colors.ink else colors.accent,
                contentColor = if (isEink) colors.surface else colors.onAccent,
            ),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(StringRes.reader_bookmark_add_current_page),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun BookmarkRenamePrompt(
    bookmark: BookmarkUiModel,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(bookmark.id) { mutableStateOf(bookmark.locatorTitle.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(StringRes.reader_bookmark_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(StringRes.reader_bookmark_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }) { Text(stringResource(StringRes.reader_bookmark_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(StringRes.reader_bookmark_rename_cancel)) }
        },
    )
}

/** " · saved 3 days ago" half of the bookmark subtitle; older saves show a date. */
@Composable
private fun savedTimeText(createdAt: String): String {
    val time = remember(createdAt) { relativeTimeFromIso(createdAt, nowMillis()) }
    val relative = when {
        time is RelativeTime.MinutesAgo -> stringResource(StringRes.reader_bookmark_minutes_ago, time.minutes)
        time is RelativeTime.HoursAgo -> stringResource(StringRes.reader_bookmark_hours_ago, time.hours)
        time is RelativeTime.DaysAgo && time.days == 1 -> stringResource(StringRes.reader_contents_saved_yesterday)
        time is RelativeTime.DaysAgo && time.days <= 7 -> stringResource(StringRes.reader_bookmark_days_ago, time.days)
        time is RelativeTime.DaysAgo -> savedDateText(createdAt)
        else -> stringResource(StringRes.reader_bookmark_just_now)
    }
    return stringResource(StringRes.reader_contents_saved_relative, relative)
}

@Composable
private fun savedDateText(createdAt: String): String {
    val date = remember(createdAt) {
        try {
            val instant = try {
                Instant.parse(createdAt)
            } catch (_: IllegalArgumentException) {
                LocalDateTime.parse(createdAt).toInstant(TimeZone.currentSystemDefault())
            }
            instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
        } catch (_: Exception) {
            null
        }
    }
    @Suppress("DEPRECATION")
    return date?.let { com.retro99.base.formatMediumDate(it.year, it.monthNumber, it.dayOfMonth) }
        ?: stringResource(StringRes.reader_bookmark_just_now)
}
