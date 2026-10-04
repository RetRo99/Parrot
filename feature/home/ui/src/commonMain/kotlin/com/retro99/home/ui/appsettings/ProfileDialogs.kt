package com.retro99.home.ui.appsettings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.base.ui.compose.EmberTextField
import com.retro99.base.ui.compose.ThemeMode
import com.retro99.base.buildconfig.BuildConfig
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.translations.StringRes
import com.retro99.user.api.UserProfile
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import resources.translations.action_delete
import resources.translations.action_edit
import resources.translations.action_rename
import resources.translations.app_settings_clear_logs
import resources.translations.app_settings_clear_logs_description
import resources.translations.app_settings_clear_current_book
import resources.translations.app_settings_clear_current_book_description
import resources.translations.app_settings_current_book_cleared
import resources.translations.app_settings_current_book_clear_failed
import resources.translations.app_settings_enable_logging
import resources.translations.app_settings_enable_logging_description
import resources.translations.app_settings_logs_cleared
import resources.translations.app_settings_log_operation_failed
import resources.translations.app_settings_log_crashes_only
import resources.translations.app_settings_log_crashes_only_description
import resources.translations.app_settings_no_logs
import resources.translations.app_settings_open_last_book
import resources.translations.app_settings_open_last_book_description
import resources.translations.app_settings_section_account
import resources.translations.app_settings_section_profiles
import resources.translations.app_settings_profile_active
import resources.translations.app_settings_profile_add
import resources.translations.app_settings_profile_add_title
import resources.translations.app_settings_profile_delete_message
import resources.translations.app_settings_profile_delete_title
import resources.translations.app_settings_profile_duplicate_index
import resources.translations.app_settings_profile_name_already_exists
import resources.translations.app_settings_profile_name_label
import resources.translations.app_settings_profile_operation_failed
import resources.translations.app_settings_profile_rename_title
import resources.translations.app_settings_reading_statistics
import resources.translations.app_settings_reading_statistics_description
import resources.translations.app_settings_reader_settings
import resources.translations.app_settings_reader_settings_description
import resources.translations.app_settings_servers
import resources.translations.app_settings_servers_description
import resources.translations.app_settings_sync_backup
import resources.translations.app_settings_sync_backup_description
import resources.translations.app_settings_section_reading
import resources.translations.app_settings_section_support
import resources.translations.app_settings_share_logs
import resources.translations.app_settings_share_logs_description
import resources.translations.app_settings_show_continue_reading
import resources.translations.app_settings_show_continue_reading_description
import resources.translations.app_settings_section_appearance
import resources.translations.app_settings_theme
import resources.translations.app_settings_theme_day
import resources.translations.app_settings_theme_eink
import resources.translations.app_settings_theme_night
import resources.translations.app_settings_theme_system
import resources.translations.app_settings_title
import resources.translations.app_settings_version
import resources.translations.general_cancel
import resources.translations.general_retry

@Composable
internal fun AddProfileDialog(
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    onNameChanged: () -> Unit,
    showError: Boolean,
    showDuplicateNameError: Boolean,
    isOperationInProgress: Boolean,
) {
    var profileName by remember { mutableStateOf("") }

    EmberDialog(
        onDismissRequest = { if (!isOperationInProgress) onDismissRequest() },
        title = stringResource(StringRes.app_settings_profile_add_title),
        content = {
            EmberTextField(
                value = profileName,
                onValueChange = {
                    profileName = it
                    onNameChanged()
                },
                label = stringResource(StringRes.app_settings_profile_name_label),
                isError = showDuplicateNameError,
                errorText = stringResource(StringRes.app_settings_profile_name_already_exists),
                enabled = !isOperationInProgress,
            )
            if (showError) {
                Text(
                    text = stringResource(StringRes.app_settings_profile_operation_failed),
                    color = Ember.colors.destructive,
                    style = Ember.type.meta.copy(fontSize = 13.sp),
                )
            }
        },
        actions = listOf(
            EmberDialogAction(
                label = stringResource(StringRes.general_cancel),
                style = EmberDialogActionStyle.Neutral,
                enabled = !isOperationInProgress,
                onClick = onCancel,
            ),
            EmberDialogAction(
                label = stringResource(StringRes.app_settings_profile_add),
                style = EmberDialogActionStyle.Main,
                enabled = profileName.isNotBlank() && !showDuplicateNameError && !isOperationInProgress,
                showProgress = isOperationInProgress,
                onClick = { onConfirm(profileName) },
            ),
        ),
    )
}

@Composable
internal fun DeleteProfileConfirmationDialog(
    profileName: String,
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    showError: Boolean,
    isOperationInProgress: Boolean,
) {
    EmberDialog(
        onDismissRequest = { if (!isOperationInProgress) onDismissRequest() },
        title = stringResource(StringRes.app_settings_profile_delete_title),
        body = AnnotatedString(stringResource(StringRes.app_settings_profile_delete_message)),
        content = {
            if (showError) {
                Text(
                    text = stringResource(StringRes.app_settings_profile_operation_failed),
                    color = Ember.colors.destructive,
                    style = Ember.type.meta.copy(fontSize = 13.sp),
                )
            }
        },
        actions = listOf(
            EmberDialogAction(
                label = stringResource(StringRes.general_cancel),
                style = EmberDialogActionStyle.Neutral,
                enabled = !isOperationInProgress,
                onClick = onCancel,
            ),
            EmberDialogAction(
                label = stringResource(StringRes.action_delete),
                style = EmberDialogActionStyle.Destructive,
                enabled = !isOperationInProgress,
                showProgress = isOperationInProgress,
                onClick = onConfirm,
            ),
        ),
    )
}
