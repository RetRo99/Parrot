package com.retro99.reader.ui.reader.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedCounts
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.ui.HighlightColorDots
import com.retro99.saved.ui.SavedExportSheet
import com.retro99.saved.ui.SavedItemDetailSheet
import com.retro99.saved.ui.SavedNoteEditorSheet
import com.retro99.saved.ui.SavedUndoBar
import com.retro99.saved.ui.of
import com.retro99.saved.ui.savedExportLabels
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.saved_bar_add_note
import resources.translations.saved_bar_bookmark_removed
import resources.translations.saved_bar_bookmarked
import resources.translations.saved_bar_highlight_removed
import resources.translations.saved_bar_highlighted
import resources.translations.saved_bar_undo
import resources.translations.saved_copy
import resources.translations.saved_ribbon_a11y
import resources.translations.saved_search
import resources.translations.saved_select_highlight
import resources.translations.saved_select_note
import resources.translations.saved_share
import resources.translations.saved_too_long
import kotlin.math.roundToInt

/** Page highlight fills, independent of the profile/chrome theme. */
private val ReaderDayHighlights = com.retro99.base.ui.compose.EmberDayHighlights.let { palette ->
    palette.copy(
        amber = palette.amber.copy(fill = Color(0xFFFBE3A6)),
        rose = palette.rose.copy(fill = Color(0xFFF8CFCB)),
        sage = palette.sage.copy(fill = Color(0xFFD3E6C4)),
        sky = palette.sky.copy(fill = Color(0xFFCCE0F2)),
    )
}

/** "Bookmarked · Add note · Undo", "Bookmark removed · Undo", or a refusal. */
@Composable
internal fun SavedBarHost(
    bar: SavedBar?,
    onSaved: (SavedAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bar == null) return
    val undo = stringResource(StringRes.saved_bar_undo)
    when (bar) {
        is SavedBar.BookmarkAdded -> SavedUndoBar(
            message = stringResource(StringRes.saved_bar_bookmarked),
            undoLabel = undo,
            onUndo = { onSaved(SavedAction.BarUndo) },
            showBookmarkIcon = true,
            actionLabel = stringResource(StringRes.saved_bar_add_note),
            onAction = { onSaved(SavedAction.BarAddNote) },
            modifier = modifier,
        )
        is SavedBar.Removed -> SavedUndoBar(
            message = stringResource(
                if (bar.items.all { item -> item.type == SavedItemType.Bookmark }) StringRes.saved_bar_bookmark_removed
                else StringRes.saved_bar_highlight_removed,
            ),
            undoLabel = undo,
            onUndo = { onSaved(SavedAction.BarUndo) },
            modifier = modifier,
        )
        is SavedBar.Highlighted -> SavedUndoBar(
            message = stringResource(StringRes.saved_bar_highlighted),
            undoLabel = undo,
            onUndo = { onSaved(SavedAction.BarUndo) },
            modifier = modifier,
        )
        is SavedBar.TooLong -> SavedUndoBar(
            message = stringResource(StringRes.saved_too_long),
            undoLabel = null,
            onUndo = { onSaved(SavedAction.BarDismiss) },
            modifier = modifier,
        )
    }
}

/** The ribbon at the top-right of a bookmarked page; tapping it opens the bookmark. */
@Composable
internal fun PageRibbon(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (Ember.style.isEink) Ember.colors.ink else Ember.colors.accent
    val description = stringResource(StringRes.saved_ribbon_a11y)
    Box(
        modifier
            .size(width = 44.dp, height = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.size(width = 22.dp, height = 38.dp).clip(RibbonShape).background(color))
    }
}

private val RibbonShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height * 0.78f)
    lineTo(0f, size.height)
    close()
}

/**
 * The toolbar for a selection: "Highlight" with four colour dots (one tap highlights and
 * closes), then Note · Copy · Search · Share. Placed below the selection, or above it when
 * there is no room, so it never covers the selected text.
 *
 * @param pageTopDp where the page's top edge sits inside this box, in dp.
 */
@Composable
internal fun SelectionToolbar(
    selection: ReaderTextSelection,
    pageTopDp: Float,
    bottomObstructionDp: Float,
    topObstructionDp: Float,
    pageBackground: Color,
    onSaved: (SavedAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val rect = selection.text.rect ?: return
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val scale = selection.text.viewport?.width?.takeIf { it > 0 }?.let { constraints.maxWidth / it }
            ?: density.density.toDouble()
        val pageTopPx = with(density) { pageTopDp.dp.roundToPx() }
        val topPx = pageTopPx + (rect.top * scale).roundToInt()
        val bottomPx = pageTopPx + (rect.bottom * scale).roundToInt()
        val availableTop = with(density) { topObstructionDp.dp.roundToPx() }
        val availableBottom = constraints.maxHeight - with(density) { bottomObstructionDp.dp.roundToPx() }
        val aboveGap = with(density) { 12.dp.roundToPx() }
        val belowGap = with(density) { 32.dp.roundToPx() }
        val position = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val y = selectionToolbarY(topPx, bottomPx, popupContentSize.height, availableTop,
                    minOf(availableBottom, windowSize.height - anchorBounds.top), aboveGap, belowGap)
                return IntOffset(anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2, anchorBounds.top + y)
            }
        }
        Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            shadowElevation = if (eink) 0.dp else 12.dp,
            border = if (eink) androidx.compose.foundation.BorderStroke(2.dp, colors.ink) else null,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .width(minOf(maxWidth, 480.dp)),
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (eink) {
                        // No colours on e-ink: one button, kept as amber for other devices.
                        ToolbarButton(
                            label = stringResource(StringRes.saved_select_highlight),
                            onClick = { onSaved(SavedAction.Highlight(HighlightColor.Default)) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            stringResource(StringRes.saved_select_highlight),
                            color = colors.ink2,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        HighlightColorDots(selected = null, onSelect = { color -> onSaved(SavedAction.Highlight(color)) },
                            palette = if (pageBackground.luminance() < 0.4f) com.retro99.base.ui.compose.EmberNightHighlights else ReaderDayHighlights,
                            compact = true, pageBackground = pageBackground)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolbarButton(stringResource(StringRes.saved_select_note), { onSaved(SavedAction.NoteSelection) }, Modifier.weight(1f))
                    ToolbarButton(stringResource(StringRes.saved_copy), { onSaved(SavedAction.CopySelection) }, Modifier.weight(1f))
                    ToolbarButton(stringResource(StringRes.saved_search), { onSaved(SavedAction.SearchSelection) }, Modifier.weight(1f))
                    ToolbarButton(stringResource(StringRes.saved_share), { onSaved(SavedAction.ShareSelection) }, Modifier.weight(1f))
                }
            }
        }
        }
    }
}

/** Coordinates are relative to the page overlay; full-page selections fall back to its foot. */
internal fun selectionToolbarY(top: Int, bottom: Int, height: Int, availableTop: Int, availableBottom: Int, aboveGap: Int, belowGap: Int): Int = when {
    bottom + belowGap >= availableTop && bottom + belowGap + height <= availableBottom -> bottom + belowGap
    top - aboveGap - height >= availableTop && top - aboveGap <= availableBottom -> top - aboveGap - height
    else -> (availableBottom - height).coerceAtLeast(availableTop)
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(colors.bg)
            .then(if (eink) Modifier.border(2.dp, colors.ink, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Detail, note and export sheets, the clipboard, and the colours the page marks are drawn in. */
@Suppress("DEPRECATION")
@Composable
internal fun ReaderSavedHost(
    saved: ReaderSavedState,
    onSaved: (SavedAction) -> Unit,
    isDarkPage: Boolean,
    marginHorizontalDp: Int,
    scroll: Boolean,
) {
    val eink = Ember.style.isEink
    val palette = if (isDarkPage) com.retro99.base.ui.compose.EmberNightHighlights else ReaderDayHighlights
    // The rules take their tone from the page, so a dark page never gets a dark rule.
    val style = SavedMarkStyle(
        fills = HighlightColor.entries.associateWith { color ->
            (if (eink) Color.Transparent else palette.of(color).fill).toArgb()
        },
        rules = HighlightColor.entries.associateWith { color ->
            (if (eink) Color.Black else palette.of(color).line).toArgb()
        },
        eink = eink,
        // The bar is the accent, matching the ribbon; e-ink has only black.
        barColor = (if (eink) Color.Black else Ember.colors.accent).toArgb(),
        marginLeftDp = marginHorizontalDp,
        marginRightDp = marginHorizontalDp,
        scroll = scroll,
    )
    LaunchedEffect(style) {
        onSaved(SavedAction.UpdateDecorationStyle(style))
    }

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(saved.effect) {
        when (val effect = saved.effect) {
            is SavedEffect.Copy -> {
                clipboard.setText(AnnotatedString(effect.text))
                onSaved(SavedAction.EffectHandled(effect.serial))
            }
            null -> Unit
        }
    }

    saved.detail?.let { item ->
        SavedItemDetailSheet(
            item = item,
            onDismiss = { onSaved(SavedAction.CloseDetail) },
            onColor = { color -> onSaved(SavedAction.SetColor(item.id, color)) },
            onEditNote = { onSaved(SavedAction.EditNote(item.id)) },
            onCopy = { onSaved(SavedAction.Copy(item.id)) },
            onShare = { onSaved(SavedAction.Share(item.id)) },
            onRemove = { onSaved(SavedAction.Remove(item.id)) },
        )
    }
    saved.noteEditorItem?.let { item ->
        SavedNoteEditorSheet(
            item = item,
            onSave = { text -> onSaved(SavedAction.SaveNote(item.id, text)) },
            onDismiss = { onSaved(SavedAction.CloseNoteEditor) },
        )
    }
    if (saved.isExportVisible) {
        val labels = savedExportLabels()
        SavedExportSheet(
            counts = SavedCounts.of(saved.items),
            onShareText = { onSaved(SavedAction.Export(SavedExportFormat.ShareText, labels)) },
            onCopyAll = { onSaved(SavedAction.Export(SavedExportFormat.CopyAll, labels)) },
            onSaveMarkdown = { onSaved(SavedAction.Export(SavedExportFormat.Markdown, labels)) },
            onDismiss = { onSaved(SavedAction.CloseExport) },
        )
    }
}

/** Small ticks on the progress strip at bookmark positions (not highlights). */
@Composable
internal fun BookmarkTicks(ticks: List<Double>, modifier: Modifier = Modifier) {
    if (ticks.isEmpty()) return
    val color = Ember.colors.ink
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 12.dp)) {
        val width = constraints.maxWidth
        val density = LocalDensity.current
        ticks.forEach { tick ->
            val x = (tick.coerceIn(0.0, 1.0) * width).roundToInt() - with(density) { 1.dp.roundToPx() }
            Box(
                Modifier
                    .offset { IntOffset(x, 0) }
                    .size(width = 2.dp, height = 12.dp)
                    .background(color),
            )
        }
        Spacer(Modifier.size(0.dp))
    }
}
