package com.retro99.home.ui.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.playback_conflict_message
import resources.translations.playback_conflict_stop_open
import resources.translations.playback_conflict_title

/**
 * Dialog shown when user tries to open a different book while audio is playing.
 * User can either stop playback and open the new book, or cancel.
 */
@Composable
fun PlaybackConflictDialog(
    state: PlaybackConflictDialogState,
    onStopAndOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(StringRes.playback_conflict_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(StringRes.playback_conflict_message, state.currentlyPlayingTitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onStopAndOpen,
            ) {
                Text(stringResource(StringRes.playback_conflict_stop_open))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}
