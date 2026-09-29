package com.retro99.home.ui.appsettings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberChevron
import com.retro99.base.ui.compose.EmberGroupCard
import com.retro99.base.ui.compose.EmberRowDivider
import com.retro99.base.ui.compose.EmberSectionHeader
import com.retro99.base.ui.compose.EmberSettingRow
import com.retro99.base.ui.compose.EmberSwitchRow
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import resources.translations.app_settings_log_operation_failed
import resources.translations.app_settings_logs_cleared
import resources.translations.app_settings_no_logs
import resources.translations.app_settings_preference_save_failed
import resources.translations.diagnostics_clear_confirm_action
import resources.translations.diagnostics_clear_confirm_body
import resources.translations.diagnostics_clear_confirm_title
import resources.translations.diagnostics_clear_logs
import resources.translations.diagnostics_clear_logs_subtitle
import resources.translations.diagnostics_crashes_only
import resources.translations.diagnostics_crashes_only_disabled
import resources.translations.diagnostics_crashes_only_subtitle
import resources.translations.diagnostics_intro
import resources.translations.diagnostics_save_logs
import resources.translations.diagnostics_save_logs_subtitle
import resources.translations.diagnostics_section_log_files
import resources.translations.diagnostics_share_logs
import resources.translations.diagnostics_share_logs_subtitle
import resources.translations.general_back
import resources.translations.general_cancel
import resources.translations.general_retry
import resources.translations.settings_diagnostics_title

/** Diagnostics: file logging options and the log-file actions. */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = koinViewModel(),
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        DiagnosticsScreenContent(
            viewState = viewState,
            onBack = onBack,
            intentDispatcher = intentDispatcher,
        )
    }
}

@Composable
private fun DiagnosticsScreenContent(
    viewState: DiagnosticsViewState,
    onBack: () -> Unit,
    intentDispatcher: IntentDispatcher<DiagnosticsIntent>,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val logsClearedMessage = stringResource(StringRes.app_settings_logs_cleared)
    val noLogsMessage = stringResource(StringRes.app_settings_no_logs)
    val logOperationFailedMessage = stringResource(StringRes.app_settings_log_operation_failed)
    val preferenceSaveFailedMessage = stringResource(StringRes.app_settings_preference_save_failed)
    val retryMessage = stringResource(StringRes.general_retry)

    LaunchedEffect(viewState.appSettingSaveFailureCount) {
        if (viewState.appSettingSaveFailureCount > 0) {
            snackbarHostState.showSnackbar(preferenceSaveFailedMessage)
        }
    }

    LaunchedEffect(viewState.showLogsClearedMessage) {
        if (viewState.showLogsClearedMessage) {
            snackbarHostState.showSnackbar(logsClearedMessage)
            intentDispatcher(DiagnosticsIntent.OnLogsClearedMessageShown)
        }
    }

    LaunchedEffect(viewState.showLogsClearFailedMessage) {
        if (viewState.showLogsClearFailedMessage) {
            val result = snackbarHostState.showSnackbar(
                message = logOperationFailedMessage,
                actionLabel = retryMessage,
            )
            intentDispatcher(DiagnosticsIntent.OnLogsClearFailedMessageShown)
            if (result == SnackbarResult.ActionPerformed) {
                intentDispatcher(DiagnosticsIntent.OnClearLogsConfirmed)
            }
        }
    }

    LaunchedEffect(viewState.showNoLogsMessage) {
        if (viewState.showNoLogsMessage) {
            snackbarHostState.showSnackbar(noLogsMessage)
            intentDispatcher(DiagnosticsIntent.OnNoLogsMessageShown)
        }
    }

    LaunchedEffect(viewState.showLogShareFailedMessage) {
        if (viewState.showLogShareFailedMessage) {
            val result = snackbarHostState.showSnackbar(
                message = logOperationFailedMessage,
                actionLabel = retryMessage,
            )
            intentDispatcher(DiagnosticsIntent.OnShareLogsFailedMessageShown)
            if (result == SnackbarResult.ActionPerformed) {
                intentDispatcher(DiagnosticsIntent.OnShareLogsClicked)
            }
        }
    }

    if (viewState.showClearLogsConfirmation) {
        AlertDialog(
            onDismissRequest = { intentDispatcher(DiagnosticsIntent.OnClearLogsDismissed) },
            containerColor = colors.surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = stringResource(StringRes.diagnostics_clear_confirm_title),
                    style = Ember.type.cardTitle,
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text = stringResource(StringRes.diagnostics_clear_confirm_body),
                    style = Ember.type.meta.copy(fontSize = 14.sp),
                    color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { intentDispatcher(DiagnosticsIntent.OnClearLogsConfirmed) }) {
                    Text(
                        text = stringResource(StringRes.diagnostics_clear_confirm_action),
                        style = Ember.type.label.copy(fontSize = 15.sp),
                        color = colors.destructive,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { intentDispatcher(DiagnosticsIntent.OnClearLogsDismissed) }) {
                    Text(
                        text = stringResource(StringRes.general_cancel),
                        style = Ember.type.label.copy(fontSize = 15.sp),
                        color = colors.accentText,
                    )
                }
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(StringRes.general_back),
                        tint = colors.ink,
                    )
                }
                Text(
                    text = stringResource(StringRes.settings_diagnostics_title),
                    style = Ember.type.screenTitle.copy(fontSize = 26.sp),
                    color = colors.ink,
                )
            }

            Text(
                text = stringResource(StringRes.diagnostics_intro),
                style = Ember.type.meta.copy(fontSize = 14.sp),
                color = colors.ink2,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            )

            EmberGroupCard {
                EmberSwitchRow(
                    title = stringResource(StringRes.diagnostics_save_logs),
                    subtitle = stringResource(StringRes.diagnostics_save_logs_subtitle),
                    checked = viewState.isLoggingEnabled,
                    onCheckedChange = { enabled ->
                        intentDispatcher(DiagnosticsIntent.OnLoggingToggled(enabled))
                    },
                )
                EmberRowDivider()
                EmberSwitchRow(
                    title = stringResource(StringRes.diagnostics_crashes_only),
                    subtitle = if (viewState.isLoggingEnabled) {
                        stringResource(StringRes.diagnostics_crashes_only_subtitle)
                    } else {
                        stringResource(StringRes.diagnostics_crashes_only_disabled)
                    },
                    checked = viewState.logCrashesOnly,
                    onCheckedChange = { enabled ->
                        intentDispatcher(DiagnosticsIntent.OnLogCrashesOnlyToggled(enabled))
                    },
                    indent = true,
                    enabled = viewState.isLoggingEnabled,
                )
            }

            EmberSectionHeader(text = stringResource(StringRes.diagnostics_section_log_files))

            EmberGroupCard {
                EmberSettingRow(
                    title = stringResource(StringRes.diagnostics_share_logs),
                    subtitle = stringResource(StringRes.diagnostics_share_logs_subtitle),
                    icon = Icons.Outlined.Share,
                    onClick = { intentDispatcher(DiagnosticsIntent.OnShareLogsClicked) },
                    trailing = { EmberChevron() },
                )
                EmberRowDivider()
                EmberSettingRow(
                    title = stringResource(StringRes.diagnostics_clear_logs),
                    subtitle = stringResource(StringRes.diagnostics_clear_logs_subtitle),
                    icon = Icons.Outlined.Delete,
                    onClick = { intentDispatcher(DiagnosticsIntent.OnClearLogsClicked) },
                    isDestructive = true,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}
