package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.books.ui.components.PositionConflictDialogContent
import com.retro99.reader.ui.model.PositionConflictUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_conflict_chapter
import resources.translations.reader_conflict_local_title
import resources.translations.reader_conflict_progress
import resources.translations.reader_conflict_remote_title

/**
 * Dialog for resolving position conflicts with full position details.
 * Used in the reader when we have complete position information.
 */
@Composable
fun PositionConflictDialog(
    conflict: PositionConflictUiModel,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
    modifier: Modifier = Modifier,
    serverName: String = "",
    isResolving: Boolean = false,
    error: String? = null,
) {
    PositionConflictDialogContent(
        localContent = {
            PositionCard(
                title = stringResource(StringRes.reader_conflict_local_title),
                position = conflict.localPosition,
                onClick = { if (!isResolving) onUseLocal() },
                detail = com.retro99.books.ui.components.positionCandidateDetail(conflict.candidates.localPosition),
            )
        },
        remoteContent = {
            Column {
                PositionCard(
                    title = serverName.ifBlank { stringResource(StringRes.reader_conflict_remote_title) },
                    position = conflict.remotePosition,
                    onClick = { if (!isResolving) onUseRemote() },
                    detail = com.retro99.books.ui.components.positionCandidateDetail(conflict.candidates.remotePosition),
                )
                error?.let { Text(it, color = Ember.colors.ink2) }
            }
        },
        onUseLocal = { if (!isResolving) onUseLocal() },
        onUseRemote = { if (!isResolving) onUseRemote() },
        onDismissRequest = { /* Don't allow dismiss without choosing */ },
        modifier = modifier,
    )
}

@Composable
private fun PositionCard(
    title: String,
    position: PositionUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ember.colors.bg)
            .border(
                if (Ember.style.isEink) 2.dp else 1.dp,
                Ember.colors.chipBorder,
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = Ember.colors.ink,
        )
        Spacer(modifier = Modifier.height(8.dp))
        detail?.let { Text(it, style = Ember.type.meta, color = Ember.colors.ink2) }
        position.title?.let { chapterTitle ->
            Text(
                text = chapterTitle,
                style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = Ember.colors.ink2,
                maxLines = 2,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        val chapterIndex = position.chapterIndex
        val totalChapters = position.totalChapters
        if (chapterIndex != null && totalChapters != null) {
            Text(
                text = stringResource(
                    StringRes.reader_conflict_chapter,
                    chapterIndex + 1,
                    totalChapters,
                ),
                style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = Ember.colors.ink2,
            )
        }
        position.totalProgression?.let { progress ->
            Text(
                text = stringResource(
                    StringRes.reader_conflict_progress,
                    (progress * 100).toInt(),
                ),
                style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = Ember.colors.accentText,
            )
        }
    }
}
