package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_conflict_local_title
import resources.translations.reader_conflict_message
import resources.translations.reader_conflict_progress
import resources.translations.reader_conflict_remote_title
import resources.translations.reader_conflict_title
import resources.translations.reader_conflict_use_local
import resources.translations.reader_conflict_use_remote

/**
 * Dialog for resolving position conflicts with just progress percentages.
 * Used in book details when we only have progress info.
 */
@Composable
fun PositionConflictDialog(
    localProgressPercent: Int,
    remoteProgressPercent: Int,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PositionConflictDialogContent(
        localContent = {
            ProgressCard(
                title = stringResource(StringRes.reader_conflict_local_title),
                progressPercent = localProgressPercent,
                onClick = onUseLocal,
            )
        },
        remoteContent = {
            ProgressCard(
                title = stringResource(StringRes.reader_conflict_remote_title),
                progressPercent = remoteProgressPercent,
                onClick = onUseRemote,
            )
        },
        onUseLocal = onUseLocal,
        onUseRemote = onUseRemote,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    )
}

/**
 * Shared dialog content for position conflict resolution.
 * Can be used with different card content (simple progress or full position details).
 * Both positions are peer choices, so both buttons carry the main-choice color.
 */
@Composable
fun PositionConflictDialogContent(
    localContent: @Composable () -> Unit,
    remoteContent: @Composable () -> Unit,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmberDialog(
        onDismissRequest = onDismissRequest,
        title = stringResource(StringRes.reader_conflict_title),
        actions = listOf(
            EmberDialogAction(
                label = stringResource(StringRes.reader_conflict_use_local),
                style = EmberDialogActionStyle.Main,
                onClick = onUseLocal,
            ),
            EmberDialogAction(
                label = stringResource(StringRes.reader_conflict_use_remote),
                style = EmberDialogActionStyle.Main,
                onClick = onUseRemote,
            ),
        ),
        modifier = modifier,
        body = AnnotatedString(stringResource(StringRes.reader_conflict_message)),
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { localContent() }
                Box(modifier = Modifier.weight(1f)) { remoteContent() }
            }
        },
    )
}

/**
 * Simple card showing just progress percentage.
 * Used when we don't have full position details.
 */
@Composable
private fun ProgressCard(
    title: String,
    progressPercent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        Text(
            text = stringResource(
                StringRes.reader_conflict_progress,
                progressPercent,
            ),
            style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = Ember.colors.accentText,
        )
    }
}
