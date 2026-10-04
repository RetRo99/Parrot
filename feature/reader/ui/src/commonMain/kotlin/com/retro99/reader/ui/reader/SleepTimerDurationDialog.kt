package com.retro99.reader.ui.reader

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.base.ui.compose.EmberTextField
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.sleep_timer_custom_hint
import resources.translations.sleep_timer_minutes_label

private const val MIN_CUSTOM_SLEEP_TIMER_MINUTES = 1
private const val MAX_CUSTOM_SLEEP_TIMER_MINUTES = 180

@Composable
internal fun SleepTimerDurationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    initialMinutes: Int = 5,
    onConfirm: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutesText by remember {
        mutableStateOf(initialMinutes.coerceInCustomTimerRange().toString())
    }
    val minutes = minutesText.toIntOrNull()
    val isValid = minutes in MIN_CUSTOM_SLEEP_TIMER_MINUTES..MAX_CUSTOM_SLEEP_TIMER_MINUTES
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
        kotlinx.coroutines.delay(250)
        keyboardController?.show()
    }

    EmberDialog(
        onDismissRequest = onDismiss,
        title = title,
        actions = listOf(
            EmberDialogAction(
                label = confirmLabel,
                style = EmberDialogActionStyle.Main,
                enabled = isValid,
                onClick = {
                    minutes?.let { selected -> onConfirm(selected.coerceInCustomTimerRange()) }
                },
            ),
            EmberDialogAction(
                label = dismissLabel,
                style = EmberDialogActionStyle.Neutral,
                onClick = onDismiss,
            ),
        ),
        body = AnnotatedString(message),
        content = {
            EmberTextField(
                value = minutesText,
                onValueChange = { text ->
                    minutesText = text
                        .filter { it.isDigit() }
                        .take(3)
                },
                label = stringResource(StringRes.sleep_timer_minutes_label),
                helperText = stringResource(
                    StringRes.sleep_timer_custom_hint,
                    MIN_CUSTOM_SLEEP_TIMER_MINUTES,
                    MAX_CUSTOM_SLEEP_TIMER_MINUTES,
                ),
                isError = minutesText.isNotEmpty() && !isValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                focusRequester = focusRequester,
            )
        },
    )
}

internal fun formatSleepTimerLabel(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val seconds = (durationMs / 1_000L) % 60L
    return if (totalMinutes > 0L) {
        "${totalMinutes}m"
    } else {
        "${seconds}s"
    }
}

private fun Int.coerceInCustomTimerRange(): Int =
    coerceIn(MIN_CUSTOM_SLEEP_TIMER_MINUTES, MAX_CUSTOM_SLEEP_TIMER_MINUTES)
