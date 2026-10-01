package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import resources.translations.book_detail_remote_further
import resources.translations.book_detail_remote_position
import resources.translations.book_detail_go_remote
import resources.translations.book_detail_use_remote
import resources.translations.book_detail_keep_local
import resources.translations.book_detail_time_left
import resources.translations.book_detail_chapter_announcement

@Composable
internal fun BookDetailProgress(
    state: BookDetailViewState,
    dispatch: IntentDispatcher<BookDetailIntent>,
) {
    val progress = state.progressInfo ?: return
    val fraction = progress.displayProgression ?: 0.0
    if (fraction <= 0 && !progress.hasConflict) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            DetailProgressBar(fraction.toFloat())
        }
        if (progress.hasConflict) {
            val remote = progress.remoteProgressPercent ?: 0
            val further = (progress.remoteProgression ?: 0.0) >
                (progress.localProgression ?: 0.0)
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.fillMaxWidth().background(Ember.colors.surface, shape)
                    .border(Ember.style.detailBorder, Ember.colors.line, shape).padding(14.dp),
            ) {
                Text(stringResource(if (further) StringRes.book_detail_remote_further
                    else StringRes.book_detail_remote_position, remote),
                    style = Ember.type.label, color = Ember.colors.ink)
                progress.remoteObservedAt?.let { time ->
                    ObservedTime.toEpochMillis(time)?.let { millis ->
                        Text(relativeTimeText(millis), style = Ember.type.meta,
                            color = Ember.colors.ink2)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailButton(
                        stringResource(if (further) StringRes.book_detail_go_remote
                            else StringRes.book_detail_use_remote, remote),
                        onClick = { dispatch(BookDetailIntent.OnUseRemotePositionClicked) },
                        enabled = !state.isResolvingConflict,
                        secondary = further,
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
}
