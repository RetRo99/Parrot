package com.retro99.reader.ui.reader

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
        mutableStateOf(
            initialMinutes
                .coerceInCustomTimerRange()
                .toString()
                .toTextFieldValueWithCursorAtEnd(),
        )
    }
    val minutes = minutesText.text.toIntOrNull()
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { value ->
                        minutesText = value.text
                            .filter { it.isDigit() }
                            .take(3)
                            .toTextFieldValueWithCursorAtEnd()
                    },
                    label = { Text(stringResource(StringRes.sleep_timer_minutes_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                StringRes.sleep_timer_custom_hint,
                                MIN_CUSTOM_SLEEP_TIMER_MINUTES,
                                MAX_CUSTOM_SLEEP_TIMER_MINUTES,
                            )
                        )
                    },
                    isError = minutesText.text.isNotEmpty() && !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedMinutes = minutes ?: return@Button
                    onConfirm(selectedMinutes.coerceInCustomTimerRange())
                },
                enabled = isValid,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
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

private fun String.toTextFieldValueWithCursorAtEnd(): TextFieldValue =
    TextFieldValue(
        text = this,
        selection = TextRange(length),
    )
