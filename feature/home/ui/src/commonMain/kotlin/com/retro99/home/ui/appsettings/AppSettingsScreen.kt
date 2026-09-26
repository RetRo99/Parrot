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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.buildconfig.BuildConfig
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.translations.StringRes
import com.retro99.user.api.UserProfile
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
import resources.translations.app_settings_enable_logging
import resources.translations.app_settings_enable_logging_description
import resources.translations.app_settings_logs_cleared
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
import resources.translations.app_settings_profile_name_label
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
import resources.translations.app_settings_title
import resources.translations.app_settings_version
import resources.translations.general_cancel

@Composable
fun AppSettingsScreen(
    onNavigateToStatistics: () -> Unit,
    onNavigateToServerManagement: () -> Unit,
    onNavigateToSyncAndBackup: () -> Unit,
    onNavigateToReaderSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppSettingsViewModel = koinViewModel(),
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        AppSettingsScreenContent(
            viewState = viewState,
            onNavigateToStatistics = onNavigateToStatistics,
            onNavigateToServerManagement = onNavigateToServerManagement,
            onNavigateToSyncAndBackup = onNavigateToSyncAndBackup,
            onNavigateToReaderSettings = onNavigateToReaderSettings,
            intentDispatcher = intentDispatcher,
        )
    }
}

@Composable
private fun AppSettingsScreenContent(
    viewState: AppSettingsViewState,
    onNavigateToStatistics: () -> Unit,
    onNavigateToServerManagement: () -> Unit,
    onNavigateToSyncAndBackup: () -> Unit,
    onNavigateToReaderSettings: () -> Unit,
    intentDispatcher: IntentDispatcher<AppSettingsIntent>,
    modifier: Modifier = Modifier,
    buildConfig: BuildConfig = koinInject(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val logsClearedMessage = stringResource(StringRes.app_settings_logs_cleared)
    val noLogsMessage = stringResource(StringRes.app_settings_no_logs)
    val currentBookClearedMessage = stringResource(StringRes.app_settings_current_book_cleared)

    LaunchedEffect(viewState.showLogsClearedMessage) {
        if (viewState.showLogsClearedMessage) {
            snackbarHostState.showSnackbar(logsClearedMessage)
            intentDispatcher(AppSettingsIntent.OnLogsClearedMessageShown)
        }
    }

    LaunchedEffect(viewState.showNoLogsMessage) {
        if (viewState.showNoLogsMessage) {
            snackbarHostState.showSnackbar(noLogsMessage)
            intentDispatcher(AppSettingsIntent.OnNoLogsMessageShown)
        }
    }

    LaunchedEffect(viewState.showCurrentBookClearedMessage) {
        if (viewState.showCurrentBookClearedMessage) {
            snackbarHostState.showSnackbar(currentBookClearedMessage)
            intentDispatcher(AppSettingsIntent.OnCurrentBookClearedMessageShown)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(StringRes.app_settings_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Profiles Section
            SettingsSectionHeader(
                title = stringResource(StringRes.app_settings_section_profiles),
            )

            ProfilesRow(
                profiles = viewState.userProfiles,
                activeProfile = viewState.activeProfile,
                selectedProfileForMenu = viewState.selectedProfileForMenu,
                onProfileSelected = { profileId ->
                    intentDispatcher(AppSettingsIntent.OnProfileSelected(profileId))
                },
                onProfileLongPressed = { profileId ->
                    intentDispatcher(AppSettingsIntent.OnProfileLongPressed(profileId))
                },
                onAddProfileClicked = {
                    intentDispatcher(AppSettingsIntent.OnAddProfileClicked)
                },
                onMenuDismissed = {
                    intentDispatcher(AppSettingsIntent.OnProfileMenuDismissed)
                },
                onRenameClicked = {
                    intentDispatcher(AppSettingsIntent.OnRenameProfileClicked)
                },
                onDeleteClicked = {
                    intentDispatcher(AppSettingsIntent.OnDeleteProfileClicked)
                },
                canDelete = viewState.canDeleteSelectedProfile,
            )

            if (viewState.showAddProfileDialog) {
                AddProfileDialog(
                    onDismiss = { intentDispatcher(AppSettingsIntent.OnAddProfileDismissed) },
                    onConfirm = { name -> intentDispatcher(AppSettingsIntent.OnAddProfileConfirmed(name)) },
                )
            }

            if (viewState.showRenameProfileDialog && viewState.selectedProfileForMenu != null) {
                RenameProfileDialog(
                    currentName = viewState.selectedProfileForMenu.name,
                    onDismiss = { intentDispatcher(AppSettingsIntent.OnRenameProfileDismissed) },
                    onConfirm = { newName -> intentDispatcher(AppSettingsIntent.OnRenameProfileConfirmed(newName)) },
                )
            }

            if (viewState.showDeleteProfileDialog && viewState.selectedProfileForMenu != null) {
                DeleteProfileConfirmationDialog(
                    profileName = viewState.selectedProfileForMenu.name,
                    onDismiss = { intentDispatcher(AppSettingsIntent.OnDeleteProfileDismissed) },
                    onConfirm = { intentDispatcher(AppSettingsIntent.OnDeleteProfileConfirmed) },
                )
            }

            HorizontalDivider()

            Spacer(modifier = Modifier.height(24.dp))

            // Reading Section
            SettingsSectionHeader(
                title = stringResource(StringRes.app_settings_section_reading),
            )

            SettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(StringRes.app_settings_reader_settings),
                description = stringResource(StringRes.app_settings_reader_settings_description),
                onClick = onNavigateToReaderSettings,
            )

            SettingsToggleItem(
                icon = Icons.Default.MenuBook,
                title = stringResource(StringRes.app_settings_open_last_book),
                description = stringResource(StringRes.app_settings_open_last_book_description),
                isChecked = viewState.openLastBookOnLaunch,
                onCheckedChange = { enabled ->
                    intentDispatcher(AppSettingsIntent.OnOpenLastBookToggled(enabled))
                },
            )

            SettingsToggleItem(
                icon = Icons.Default.MenuBook,
                title = stringResource(StringRes.app_settings_show_continue_reading),
                description = stringResource(StringRes.app_settings_show_continue_reading_description),
                isChecked = viewState.showContinueReading,
                onCheckedChange = { enabled ->
                    intentDispatcher(AppSettingsIntent.OnShowContinueReadingToggled(enabled))
                },
            )

            if (viewState.hasCurrentlyReadingBook) {
                SettingsItem(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(StringRes.app_settings_clear_current_book),
                    description = stringResource(StringRes.app_settings_clear_current_book_description),
                    isDestructive = true,
                    onClick = { intentDispatcher(AppSettingsIntent.OnClearCurrentBookClicked) },
                )
            }

            SettingsItem(
                icon = Icons.Default.BarChart,
                title = stringResource(StringRes.app_settings_reading_statistics),
                description = stringResource(StringRes.app_settings_reading_statistics_description),
                onClick = onNavigateToStatistics,
            )

            HorizontalDivider()

            Spacer(modifier = Modifier.height(24.dp))

            // Account Section: adding servers and syncing sit above Support
            // because they are the tasks people come here for.
            SettingsSectionHeader(
                title = stringResource(StringRes.app_settings_section_account),
            )

            SettingsItem(
                icon = Icons.Default.Dns,
                title = stringResource(StringRes.app_settings_servers),
                description = stringResource(StringRes.app_settings_servers_description),
                onClick = onNavigateToServerManagement,
            )

            SettingsItem(
                icon = Icons.Default.Cloud,
                title = stringResource(StringRes.app_settings_sync_backup),
                description = stringResource(StringRes.app_settings_sync_backup_description),
                onClick = onNavigateToSyncAndBackup,
            )

            HorizontalDivider()

            Spacer(modifier = Modifier.height(24.dp))

            // Support Section
            SettingsSectionHeader(
                title = stringResource(StringRes.app_settings_section_support),
            )

            SettingsToggleItem(
                icon = Icons.Default.Description,
                title = stringResource(StringRes.app_settings_enable_logging),
                description = stringResource(StringRes.app_settings_enable_logging_description),
                isChecked = viewState.isLoggingEnabled,
                onCheckedChange = { enabled ->
                    intentDispatcher(AppSettingsIntent.OnLoggingToggled(enabled))
                },
            )

            SettingsToggleItem(
                icon = Icons.Default.Description,
                title = stringResource(StringRes.app_settings_log_crashes_only),
                description = stringResource(StringRes.app_settings_log_crashes_only_description),
                isChecked = viewState.logCrashesOnly,
                enabled = viewState.isLoggingEnabled,
                onCheckedChange = { enabled ->
                    intentDispatcher(AppSettingsIntent.OnLogCrashesOnlyToggled(enabled))
                },
            )

            SettingsItem(
                icon = Icons.Default.Share,
                title = stringResource(StringRes.app_settings_share_logs),
                description = stringResource(StringRes.app_settings_share_logs_description),
                onClick = { intentDispatcher(AppSettingsIntent.OnShareLogsClicked) },
            )

            SettingsItem(
                icon = Icons.Default.DeleteSweep,
                title = stringResource(StringRes.app_settings_clear_logs),
                description = stringResource(StringRes.app_settings_clear_logs_description),
                onClick = { intentDispatcher(AppSettingsIntent.OnClearLogsClicked) },
            )

            HorizontalDivider()

            // Version info at the end of the scrolling content, so it never overlaps rows
            Text(
                text = stringResource(
                    StringRes.app_settings_version,
                    buildConfig.versionName,
                    buildConfig.versionCode,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 32.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
) {
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    description: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!isChecked) }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun ProfilesRow(
    profiles: List<UserProfile>,
    activeProfile: UserProfile?,
    selectedProfileForMenu: UserProfile?,
    onProfileSelected: (String) -> Unit,
    onProfileLongPressed: (String) -> Unit,
    onAddProfileClicked: () -> Unit,
    onMenuDismissed: () -> Unit,
    onRenameClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(profiles, key = { it.id }) { profile ->
            ProfileItem(
                profile = profile,
                isActive = profile.id == activeProfile?.id,
                isMenuVisible = selectedProfileForMenu?.id == profile.id,
                onClick = { onProfileSelected(profile.id) },
                onLongClick = { onProfileLongPressed(profile.id) },
                onMenuDismissed = onMenuDismissed,
                onRenameClicked = onRenameClicked,
                onDeleteClicked = onDeleteClicked,
                canDelete = canDelete,
            )
        }
        item(key = "add_profile") {
            AddProfileItem(onClick = onAddProfileClicked)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileItem(
    profile: UserProfile,
    isActive: Boolean,
    isMenuVisible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuDismissed: () -> Unit,
    onRenameClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
) {
    Box {
        Card(
            modifier = modifier
                .width(80.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(StringRes.app_settings_profile_active),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Visible edit affordance: opens the same menu as long-press so profile
        // management never depends on a hidden gesture.
        IconButton(
            onClick = onLongClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(StringRes.action_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = isMenuVisible,
            onDismissRequest = onMenuDismissed,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(StringRes.action_rename)) },
                onClick = onRenameClicked,
            )
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(StringRes.action_delete)) },
                    onClick = onDeleteClicked,
                )
            }
        }
    }
}

@Composable
private fun AddProfileItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(80.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(StringRes.app_settings_profile_add),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(StringRes.app_settings_profile_add),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AddProfileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var profileName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(StringRes.app_settings_profile_add_title))
        },
        text = {
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text(stringResource(StringRes.app_settings_profile_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(profileName) },
                enabled = profileName.isNotBlank(),
            ) {
                Text(stringResource(StringRes.app_settings_profile_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}

@Composable
private fun RenameProfileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var profileName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(StringRes.app_settings_profile_rename_title))
        },
        text = {
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text(stringResource(StringRes.app_settings_profile_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(profileName) },
                enabled = profileName.isNotBlank(),
            ) {
                Text(stringResource(StringRes.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}

@Composable
private fun DeleteProfileConfirmationDialog(
    profileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(StringRes.app_settings_profile_delete_title))
        },
        text = {
            Text(text = stringResource(StringRes.app_settings_profile_delete_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(StringRes.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}
