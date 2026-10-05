package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_tts_stop_preview
import resources.translations.reader_voices_preview_after_download
import resources.translations.reader_voices_preview_voice

private const val UNAVAILABLE_ALPHA = 0.55f
private val ROW_MIN_HEIGHT = 56.dp
private val PREVIEW_TARGET = 48.dp
private val PREVIEW_VISUAL = 44.dp

/**
 * One selectable voice: radio, name with an optional caption, and a preview button. Rows carry
 * no download state — that lives in the pack header. An unavailable row is dimmed but still
 * clickable so tapping it can start the pack download.
 */
@Composable
internal fun VoiceRow(
    title: String,
    caption: String?,
    selected: Boolean,
    available: Boolean,
    isEink: Boolean,
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    modifier: Modifier = Modifier,
    captionIsAccent: Boolean = false,
) {
    val colors = Ember.colors
    Row(
        modifier
            .heightIn(min = ROW_MIN_HEIGHT)
            .background(if (selected && !isEink) colors.navActive.copy(alpha = 0.5f) else colors.surface)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).alpha(if (available) 1f else UNAVAILABLE_ALPHA),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = if (isEink) colors.ink else colors.accent,
                    unselectedColor = colors.ink2,
                ),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    title,
                    color = colors.ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    Text(
                        caption,
                        color = if (captionIsAccent) colors.accentText else colors.ink2,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        PreviewButton(
            name = title,
            enabled = available,
            isPreviewing = isPreviewing,
            isPreviewPlaying = isPreviewPlaying,
            isEink = isEink,
            onPreview = onPreview,
            onStopPreview = onStopPreview,
        )
    }
}

/** 44dp circular preview button inside a 48dp touch target. */
@Composable
private fun PreviewButton(
    name: String,
    enabled: Boolean,
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    isEink: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    val colors = Ember.colors
    val active = isPreviewing && isPreviewPlaying
    val loading = isPreviewing && !isPreviewPlaying
    val description = when {
        !enabled -> stringResource(StringRes.reader_voices_preview_after_download)
        active || loading -> stringResource(StringRes.reader_tts_stop_preview)
        else -> stringResource(StringRes.reader_voices_preview_voice, name)
    }
    Box(
        Modifier
            .size(PREVIEW_TARGET)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button) {
                if (active || loading) onStopPreview() else onPreview()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(PREVIEW_VISUAL)
                .clip(CircleShape)
                .alpha(if (enabled) 1f else UNAVAILABLE_ALPHA / 2)
                .border(
                    if (isEink) 2.dp else 1.dp,
                    if (isEink) colors.line else colors.chipBorder,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = if (isEink) colors.ink else colors.accent,
                    strokeWidth = 2.dp,
                )
                else -> Icon(
                    if (active) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = colors.ink,
                )
            }
        }
    }
}
