package com.retro99.saved.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.saved.domain.SavedItemsExport
import com.retro99.saved.domain.model.SavedFilter
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedSyncState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.saved_bookmark_this_page
import resources.translations.saved_delete_a11y
import resources.translations.saved_done
import resources.translations.saved_edit
import resources.translations.saved_empty_body
import resources.translations.saved_empty_title
import resources.translations.saved_export
import resources.translations.saved_filter_all
import resources.translations.saved_filter_bookmarks
import resources.translations.saved_filter_highlights
import resources.translations.saved_filter_notes
import resources.translations.saved_note_label
import resources.translations.saved_sync_offline
import resources.translations.saved_sync_sign_in
import resources.translations.saved_sync_synced
import resources.translations.saved_sync_synced_ago
import resources.translations.saved_sync_syncing
import kotlin.time.Clock
import kotlin.time.Instant

/** Rows of one book in book order, grouped under chapter headers. */
@Composable
fun SavedBookList(
    items: List<SavedItem>,
    filter: SavedFilter,
    onFilter: (SavedFilter) -> Unit,
    isEditing: Boolean,
    syncState: SavedSyncState,
    onOpen: (SavedItem) -> Unit,
    onDetails: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
    onToggleEdit: () -> Unit,
    onExport: () -> Unit,
    onSignIn: (() -> Unit)?,
    modifier: Modifier = Modifier,
    quoteFont: FontFamily? = null,
    /** Shown in the empty state; null hides the button (e.g. outside the reader). */
    onBookmarkThisPage: (() -> Unit)? = null,
) {
    if (items.isEmpty()) {
        Column(modifier) {
            SavedEmptyState(onBookmarkThisPage = onBookmarkThisPage, modifier = Modifier.weight(1f))
            SavedFooter(syncState, isEditing = false, canEdit = false, onToggleEdit, onExport, onSignIn)
        }
        return
    }
    val visible = items.filter(filter::matches)
    val untitled = savedExportLabels().untitledChapter
    val groups = SavedItemsExport.groupByChapter(visible, SavedItemsExport.Labels("", "", "", "", untitled))
    val now = Clock.System.now()
    Column(modifier) {
        SavedFilterChips(filter, onFilter, Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            groups.forEachIndexed { index, (chapter, chapterItems) ->
                chapterHeader(index, chapter)
                items(chapterItems, key = { item -> item.id }) { item ->
                    SavedItemRow(
                        item = item,
                        now = now,
                        isEditing = isEditing,
                        quoteFont = quoteFont,
                        onClick = { onOpen(item) },
                        onLongClick = { onDetails(item) },
                        onDelete = { onDelete(item) },
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
        SavedFooter(syncState, isEditing, canEdit = true, onToggleEdit, onExport, onSignIn)
    }
}

private fun LazyListScope.chapterHeader(index: Int, title: String) {
    item(key = "chapter-$index") {
        EmberSectionLabel(
            text = title,
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                .semantics { heading() },
        )
    }
}

@Composable
fun SavedFilterChips(
    filter: SavedFilter,
    onFilter: (SavedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SavedFilter.entries.forEach { option ->
            SavedFilterChip(
                label = stringResource(
                    when (option) {
                        SavedFilter.All -> StringRes.saved_filter_all
                        SavedFilter.Bookmarks -> StringRes.saved_filter_bookmarks
                        SavedFilter.Highlights -> StringRes.saved_filter_highlights
                        SavedFilter.Notes -> StringRes.saved_filter_notes
                    },
                ),
                selected = option == filter,
                onClick = { onFilter(option) },
            )
        }
    }
}

@Composable
private fun SavedFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val background = when {
        !selected -> Color.Transparent
        eink -> colors.ink
        else -> colors.accent
    }
    val content = when {
        !selected -> colors.ink
        eink -> colors.surface
        else -> colors.onAccent
    }
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(background)
            .border(if (eink) 2.dp else 1.dp, if (selected) background else colors.chipBorder, CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

/** One saved item: colour bar, quote (3 lines), note box and meta line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedItemRow(
    item: SavedItem,
    isEditing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Clock.System.now(),
    quoteFont: FontFamily? = null,
    /** Hide the bottom divider for the last row of a card. */
    showDivider: Boolean = true,
) {
    val colors = Ember.colors
    val description = item.accessibilityText()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(IntrinsicSize.Min)
                    .clearAndSetSemantics { contentDescription = description },
            ) {
                Box(
                    Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(item.barColor()),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.displayText(),
                        style = quoteStyle(quoteFont),
                        color = colors.ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (Ember.style.isEink && item.color != null) TextDecoration.Underline else null,
                    )
                    if (item.hasNote) {
                        SavedNoteBox(item.note!!, Modifier.padding(top = 10.dp))
                    }
                    Text(
                        text = item.metaLine(now),
                        style = Ember.type.meta.copy(fontSize = 14.sp),
                        color = colors.ink2,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (isEditing) {
                IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(StringRes.saved_delete_a11y, item.displayText().take(40)),
                        tint = colors.error,
                    )
                }
            }
        }
        if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

/** "**Note** · text" in a tinted box. */
@Composable
fun SavedNoteBox(note: String, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
            append(stringResource(StringRes.saved_note_label))
            pop()
            append(" · ")
            append(note.trim())
        },
        style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp),
        color = colors.ink,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (eink) Color.Transparent else colors.chip)
            .then(if (eink) Modifier.border(1.dp, colors.line, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
fun quoteStyle(font: FontFamily? = null) = Ember.type.bookTitle.copy(
    fontFamily = font ?: Ember.type.bookTitle.fontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 24.sp,
)

@Composable
fun SavedFooter(
    syncState: SavedSyncState,
    isEditing: Boolean,
    canEdit: Boolean,
    onToggleEdit: () -> Unit,
    onExport: () -> Unit,
    onSignIn: (() -> Unit)?,
) {
    val colors = Ember.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SavedSyncText(syncState, onSignIn, Modifier.weight(1f))
            if (canEdit) {
                Text(
                    text = stringResource(if (isEditing) StringRes.saved_done else StringRes.saved_edit),
                    color = colors.accentText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .combinedClickableButton(onToggleEdit)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
                Text(
                    text = stringResource(StringRes.saved_export),
                    color = colors.ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .border(if (Ember.style.isEink) 2.dp else 1.dp, colors.chipBorder, CircleShape)
                        .combinedClickableButton(onExport)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** The sync line, in words, where the list ends. */
@Composable
fun SavedSyncText(
    syncState: SavedSyncState,
    onSignIn: (() -> Unit)?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    val colors = Ember.colors
    val style = Ember.type.meta.copy(fontSize = 14.sp)
    when (syncState) {
        SavedSyncState.Unknown -> Spacer(modifier)
        SavedSyncState.SignedOut -> Text(
            text = stringResource(StringRes.saved_sync_sign_in),
            style = style,
            color = if (onSignIn != null) colors.accentText else colors.ink2,
            fontWeight = if (onSignIn != null) FontWeight.Bold else null,
            textAlign = textAlign,
            modifier = modifier.then(
                if (onSignIn != null) {
                    Modifier.combinedClickableButton(onSignIn, role = Role.Button).padding(vertical = 12.dp)
                } else {
                    Modifier
                },
            ),
        )
        is SavedSyncState.Synced -> Text(
            text = syncState.lastSyncedAt?.let { value -> Instant.parseOrNull(value) }
                ?.let { at -> stringResource(StringRes.saved_sync_synced_ago, agoText(at)) }
                ?: stringResource(StringRes.saved_sync_synced),
            style = style,
            color = colors.ink2,
            textAlign = textAlign,
            modifier = modifier,
        )
        SavedSyncState.Syncing -> Text(stringResource(StringRes.saved_sync_syncing), modifier, colors.ink2, style = style, textAlign = textAlign)
        SavedSyncState.WaitingForNetwork -> Text(stringResource(StringRes.saved_sync_offline), modifier, colors.ink2, style = style, textAlign = textAlign)
    }
}

@Composable
fun SavedEmptyState(onBookmarkThisPage: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (Ember.style.isEink) Color.Transparent else colors.chip)
                .border(if (Ember.style.isEink) 2.dp else 0.dp, colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(30.dp))
        }
        Text(
            stringResource(StringRes.saved_empty_title),
            style = Ember.type.cardTitle,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp).semantics { heading() },
        )
        Text(
            stringResource(StringRes.saved_empty_body),
            style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 22.sp),
            color = colors.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (onBookmarkThisPage != null) {
            SavedFilledButton(
                text = stringResource(StringRes.saved_bookmark_this_page),
                onClick = onBookmarkThisPage,
                modifier = Modifier.padding(top = 22.dp),
            )
        }
    }
}

/** The one filled action of a sheet or empty state. */
@Composable
fun SavedFilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (eink) colors.ink else colors.accent)
            .combinedClickableButton(onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (eink) colors.surface else colors.onAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

internal fun Modifier.combinedClickableButton(onClick: () -> Unit, role: Role = Role.Button): Modifier =
    this.then(Modifier.clickable(role = role, onClick = onClick))
