package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.sync.domain.ObservedTime
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_chapter_progress
import resources.translations.book_detail_progress_announcement
import resources.translations.book_detail_percent
import resources.translations.book_detail_go_remote
import resources.translations.book_detail_use_remote
import resources.translations.book_detail_keep_local
import resources.translations.book_detail_time_left
import resources.translations.book_detail_chapter_announcement
import resources.translations.book_detail_another_device
import resources.translations.book_detail_device_position
import resources.translations.book_detail_device_further

@Composable
internal fun BookDetailReadingSection(
    state: BookDetailViewState,
    dispatch: IntentDispatcher<BookDetailIntent>,
    actions: @Composable () -> Unit,
) {
    val progress = state.progressInfo
    if (progress == null) {
        actions()
        return
    }
    val fraction = progress.displayProgression ?: 0.0
    var behindHintDismissed by rememberSaveable(progress.bookUuid, progress.remoteProgression,
        progress.remoteObservedAt) { mutableStateOf(false) }
    val initialLocal = rememberSaveable(progress.bookUuid, progress.remoteProgression,
        progress.remoteObservedAt) { progress.localProgression ?: 0.0 }
    if (fraction <= 0 && !progress.hasConflict) {
        actions()
        return
    }
    val further = (progress.remoteProgression ?: 0.0) >
        (progress.localProgression ?: 0.0)
    val showBehindHint = progress.hasConflict && !further && !behindHintDismissed &&
        (progress.localProgression ?: 0.0) <= initialLocal
    Column {
        if (fraction > 0) {
            val chapter = progress.chapterIndex?.plus(1)
            val total = progress.totalChapters?.takeIf { count -> count > 0 }
            val label = if (chapter != null && total != null) stringResource(
                StringRes.book_detail_chapter_progress, progress.progressPercent, chapter, total,
            ) else stringResource(StringRes.book_detail_percent, progress.progressPercent)
            val announcement = if (chapter != null && total != null) stringResource(
                StringRes.book_detail_chapter_announcement, progress.progressPercent, chapter, total,
            ) else stringResource(
                StringRes.book_detail_progress_announcement, progress.progressPercent,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = Ember.type.meta.copy(fontWeight = FontWeight.Bold),
                    color = Ember.colors.ink, modifier = Modifier.weight(1f)
                        .semantics { contentDescription = announcement })
                val duration = progress.totalDurationMs ?: state.book?.audioDurationMs
                val remaining = duration?.takeIf { millis -> millis > 0 }?.let { millis ->
                    (millis - (progress.bookTimeMs ?: (millis * fraction).toLong()))
                        .coerceAtLeast(0)
                }
                if (remaining != null) {
                    Text(stringResource(StringRes.book_detail_time_left, durationText(remaining)),
                        style = Ember.type.meta, color = Ember.colors.ink2)
                }
            }
            Spacer(Modifier.height(8.dp))
            DetailProgressBar(fraction.toFloat(), marker =
                progress.remoteProgression?.toFloat()?.takeIf { showBehindHint })
        }
        if (progress.hasConflict) {
            val remote = progress.remoteProgressPercent ?: 0
            val device = progress.remoteDeviceName?.trim()?.takeIf { it.isNotEmpty() }
                ?: stringResource(StringRes.book_detail_another_device)
            val observed = progress.remoteObservedAt?.let { time ->
                ObservedTime.toEpochMillis(time)?.let { relativeTimeText(it) }
            }
            if (!further) {
                if (showBehindHint) {
                    Spacer(Modifier.height(10.dp))
                    val position = stringResource(StringRes.book_detail_device_position, device, remote)
                    val emphasis = SpanStyle(color = Ember.colors.ink, fontWeight = FontWeight.Bold)
                    val label = buildAnnotatedString {
                        append(position)
                        observed?.let { append(" · $it") }
                        val deviceStart = position.indexOf(device)
                        if (deviceStart >= 0) addStyle(emphasis, deviceStart, deviceStart + device.length)
                        val percent = "$remote%"
                        val percentStart = position.lastIndexOf(percent)
                        if (percentStart >= 0) addStyle(emphasis, percentStart, percentStart + percent.length)
                    }
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = {}, onLongClick = { behindHintDismissed = true },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            label,
                            style = Ember.type.meta.copy(fontSize = 14.sp),
                            color = Ember.colors.ink2,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BehindPositionPill(
                            text = stringResource(StringRes.book_detail_use_remote, remote),
                            enabled = !state.isResolvingConflict,
                            onClick = { dispatch(BookDetailIntent.OnUseRemotePositionClicked) },
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier.fillMaxWidth().background(Ember.colors.surface, shape)
                        .border(Ember.style.detailBorder, Ember.colors.line, shape).padding(14.dp),
                ) {
                    Text(stringResource(StringRes.book_detail_device_further, device, remote),
                        style = Ember.type.label, color = Ember.colors.ink)
                    observed?.let {
                        Text(it, style = Ember.type.meta,
                            color = Ember.colors.ink2)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailButton(
                            stringResource(StringRes.book_detail_go_remote, remote),
                            onClick = { dispatch(BookDetailIntent.OnUseRemotePositionClicked) },
                            enabled = !state.isResolvingConflict,
                            secondary = true,
                        )
                        TextButton(
                            onClick = { dispatch(BookDetailIntent.OnUseLocalPositionClicked) },
                            enabled = !state.isResolvingConflict,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(StringRes.book_detail_keep_local),
                                color = Ember.colors.accentText, style = Ember.type.meta)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(if (showBehindHint) 16.dp else 20.dp))
        actions()
    }
}

@Composable
private fun BehindPositionPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    // Keep the outline compact while making the entire 48dp-high area tappable.
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp).height(48.dp).clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(36.dp)
                .border(if (Ember.style.isEink) 2.dp else 1.5.dp, Ember.colors.chipBorder, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = Ember.colors.accentText,
                style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1)
        }
    }
}
