package com.retro99.saved.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedCounts
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemLimits
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.translations.PluralRes
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_contents_saved_relative
import resources.translations.saved_close
import resources.translations.saved_color_selected
import resources.translations.saved_copy
import resources.translations.saved_count_bookmarks
import resources.translations.saved_count_highlights
import resources.translations.saved_count_notes
import resources.translations.saved_detail_add_note
import resources.translations.saved_detail_remove
import resources.translations.saved_export_copy
import resources.translations.saved_export_markdown
import resources.translations.saved_export_share_text
import resources.translations.saved_export_title
import resources.translations.saved_kind_bookmark
import resources.translations.saved_kind_highlight
import resources.translations.saved_note_discard
import resources.translations.saved_note_discard_title
import resources.translations.saved_note_hint
import resources.translations.saved_note_keep
import resources.translations.saved_note_label
import resources.translations.saved_note_save
import resources.translations.saved_share
import resources.translations.saved_untitled_chapter

/**
 * "Highlight" or "Bookmark": where it is, the quote with its colour bar, the colour
 * (highlights only), the note, and Copy · Share · Remove.
 */
@Composable
fun SavedItemDetailSheet(
    item: SavedItem,
    onDismiss: () -> Unit,
    onColor: (HighlightColor) -> Unit,
    onEditNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    quoteFont: FontFamily? = null,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    EmberBottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 12.dp, bottom = 20.dp),
        ) {
            SheetTitleRow(
                title = stringResource(
                    if (item.type == SavedItemType.Highlight) StringRes.saved_kind_highlight
                    else StringRes.saved_kind_bookmark,
                ),
                onClose = onDismiss,
            )
            val chapter = item.location.chapterTitle?.takeIf { title -> title.isNotBlank() }
                ?: stringResource(StringRes.saved_untitled_chapter)
            val where = item.whereText()
            Text(
                text = listOfNotNull(
                    chapter,
                    where,
                    stringResource(StringRes.reader_contents_saved_relative, agoText(item.createdAt)),
                ).joinToString(" · "),
                style = Ember.type.meta.copy(fontSize = 14.sp),
                color = colors.ink2,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, end = 12.dp).height(IntrinsicSize.Min),
            ) {
                Box(
                    Modifier.width(5.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(item.barColor()),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = item.displayText(),
                    style = quoteStyle(quoteFont).copy(fontSize = 19.sp, lineHeight = 28.sp),
                    color = colors.ink,
                )
            }
            if (item.type == SavedItemType.Highlight && !eink) {
                HighlightColorDots(
                    selected = item.color ?: HighlightColor.Default,
                    onSelect = onColor,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
            NoteField(
                note = item.note,
                onClick = onEditNote,
                modifier = Modifier.padding(top = 18.dp, end = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetAction(Icons.Outlined.ContentCopy, stringResource(StringRes.saved_copy), onCopy, Modifier.weight(1f))
                SheetAction(Icons.Outlined.Share, stringResource(StringRes.saved_share), onShare, Modifier.weight(1f))
                SheetAction(
                    icon = null,
                    label = stringResource(StringRes.saved_detail_remove),
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                    tint = colors.error,
                )
            }
        }
    }
}

/** The tappable note box: the note, or "Add a note…". */
@Composable
private fun NoteField(note: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(14.dp)
    val hasNote = !note.isNullOrBlank()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (eink) Color.Transparent else colors.chip)
            .border(if (eink) 2.dp else 1.dp, if (eink) colors.line else colors.chipBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Notes, null, tint = colors.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        if (hasNote) {
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                    append(stringResource(StringRes.saved_note_label))
                    pop()
                    append(" · ")
                    append(note!!.trim())
                },
                style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp),
                color = colors.ink,
            )
        } else {
            Text(
                stringResource(StringRes.saved_detail_add_note),
                style = Ember.type.meta.copy(fontSize = 15.sp),
                color = colors.ink2,
            )
        }
    }
}

/** Amber, Rose, Sage, Sky: 44dp targets, named, the selected one ringed. */
@Composable
fun HighlightColorDots(
    selected: HighlightColor?,
    onSelect: (HighlightColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HighlightColor.entries.forEach { color ->
            val isSelected = color == selected
            val name = color.label()
            val description = if (isSelected) stringResource(StringRes.saved_color_selected, name) else name
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(color) })
                    .semantics {
                        contentDescription = description
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (isSelected) 40.dp else 34.dp)
                        .border(if (isSelected) 2.dp else 0.dp, if (isSelected) colors.ink else Color.Transparent, CircleShape)
                        .padding(if (isSelected) 4.dp else 0.dp)
                        .clip(CircleShape)
                        .background(colors.highlights.of(color).fill)
                        .border(1.dp, colors.highlights.of(color).bar, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, null, tint = colors.ink, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * The note editor: the quote on top, a focused multi-line field, and one filled "Save
 * note". Leaving with unsaved text asks "Discard this note?".
 */
@Composable
fun SavedNoteEditorSheet(
    item: SavedItem,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    quoteFont: FontFamily? = null,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val original = item.note.orEmpty()
    var text by rememberSaveable(item.id) { mutableStateOf(original) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val dirty = text.trim() != original.trim()
    val close = { if (dirty) confirmDiscard = true else onDismiss() }

    EmberBottomSheet(onDismiss = close) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 24.dp, end = 12.dp, bottom = 16.dp)) {
            SheetTitleRow(title = stringResource(StringRes.saved_note_label), onClose = close)
            Row(Modifier.fillMaxWidth().padding(end = 12.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(item.barColor()))
                Spacer(Modifier.width(14.dp))
                Text(
                    item.displayText(),
                    style = quoteStyle(quoteFont),
                    color = colors.ink2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val shape = RoundedCornerShape(14.dp)
            BasicTextField(
                value = text,
                onValueChange = { value -> text = value.take(SavedItemLimits.MAX_NOTE_CHARS) },
                textStyle = Ember.type.meta.copy(fontSize = 17.sp, lineHeight = 24.sp, color = colors.ink),
                cursorBrush = SolidColor(if (eink) colors.ink else colors.accent),
                minLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, end = 12.dp)
                    .heightIn(min = 120.dp)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(colors.bg)
                            .border(2.dp, if (eink) colors.line else colors.accent, shape)
                            .padding(14.dp),
                    ) {
                        if (text.isEmpty()) {
                            Text(stringResource(StringRes.saved_note_hint), color = colors.ink2, fontSize = 17.sp)
                        }
                        inner()
                    }
                },
            )
            SavedFilledButton(
                text = stringResource(StringRes.saved_note_save),
                onClick = { onSave(text.trim()) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, end = 12.dp),
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(StringRes.saved_note_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }) { Text(stringResource(StringRes.saved_note_discard), color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(StringRes.saved_note_keep)) }
            },
            containerColor = colors.surface,
        )
    }
}

/** "Export from this book" with its counts, then the three ways out. */
@Composable
fun SavedExportSheet(
    counts: SavedCounts,
    onShareText: () -> Unit,
    onCopyAll: () -> Unit,
    onSaveMarkdown: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Ember.colors
    EmberBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 20.dp)) {
            SheetTitleRow(title = stringResource(StringRes.saved_export_title), onClose = onDismiss)
            Text(
                text = savedCountsText(counts),
                style = Ember.type.meta.copy(fontSize = 14.sp),
                color = colors.ink2,
            )
            Column(Modifier.padding(top = 16.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExportOption(Icons.Outlined.Share, stringResource(StringRes.saved_export_share_text), onShareText)
                ExportOption(Icons.Outlined.ContentCopy, stringResource(StringRes.saved_export_copy), onCopyAll)
                ExportOption(Icons.Outlined.Description, stringResource(StringRes.saved_export_markdown), onSaveMarkdown)
            }
        }
    }
}

/** "3 bookmarks · 12 highlights · 4 notes", leaving out zero counts. */
@Composable
fun savedCountsText(counts: SavedCounts): String = listOfNotNull(
    counts.bookmarks.takeIf { it > 0 }?.let { n -> pluralStringResource(PluralRes.saved_count_bookmarks, n, n) },
    counts.highlights.takeIf { it > 0 }?.let { n -> pluralStringResource(PluralRes.saved_count_highlights, n, n) },
    counts.notes.takeIf { it > 0 }?.let { n -> pluralStringResource(PluralRes.saved_count_notes, n, n) },
).joinToString(" · ")

@Composable
private fun ExportOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(if (Ember.style.isEink) 2.dp else 1.dp, colors.chipBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SheetTitleRow(title: String, onClose: () -> Unit) {
    val colors = Ember.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = Ember.type.cardTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
            color = colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, stringResource(StringRes.saved_close), tint = colors.ink)
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ember.colors.ink,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (Ember.style.isEink) Color.Transparent else colors.chip)
            .then(if (Ember.style.isEink) Modifier.border(2.dp, colors.line, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}
