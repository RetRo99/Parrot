package com.retro99.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.notification_permission_message
import resources.translations.notification_permission_open_settings
import resources.translations.notification_permission_rationale_message
import resources.translations.notification_permission_title
import resources.translations.notification_permission_try_again

/**
 * Dialog shown when the user denies notification permission.
 * Informs the user that audio playback requires notification permission
 * and provides an option to open app settings or try again.
 *
 * @param showRationale If true, shows a rationale message with "Try Again" button.
 *                      If false, shows a settings message with "Open Settings" button.
 * @param onOpenSettings Callback when user wants to open app settings
 * @param onTryAgain Callback when user wants to try requesting permission again
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun NotificationPermissionDeniedDialog(
    showRationale: Boolean = false,
    onOpenSettings: () -> Unit,
    onTryAgain: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = if (showRationale) {
        stringResource(StringRes.notification_permission_rationale_message)
    } else {
        stringResource(StringRes.notification_permission_message)
    }

    EmberDialog(
        onDismissRequest = onDismiss,
        title = stringResource(StringRes.notification_permission_title),
        actions = listOf(
            EmberDialogAction(
                label = if (showRationale) {
                    stringResource(StringRes.notification_permission_try_again)
                } else {
                    stringResource(StringRes.notification_permission_open_settings)
                },
                style = EmberDialogActionStyle.Main,
                onClick = if (showRationale) onTryAgain else onOpenSettings,
            ),
            EmberDialogAction(
                label = stringResource(StringRes.general_cancel),
                style = EmberDialogActionStyle.Neutral,
                onClick = onDismiss,
            ),
        ),
        modifier = modifier,
        body = AnnotatedString(message),
    )
}
