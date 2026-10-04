package com.retro99.home.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
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
    EmberDialog(
        onDismissRequest = onDismiss,
        title = stringResource(StringRes.playback_conflict_title),
        body = AnnotatedString(
            stringResource(StringRes.playback_conflict_message, state.currentlyPlayingTitle),
        ),
        actions = listOf(
            EmberDialogAction(
                label = stringResource(StringRes.general_cancel),
                style = EmberDialogActionStyle.Neutral,
                onClick = onDismiss,
            ),
            EmberDialogAction(
                label = stringResource(StringRes.playback_conflict_stop_open),
                style = EmberDialogActionStyle.Main,
                onClick = onStopAndOpen,
            ),
        ),
        modifier = modifier,
    )
}
