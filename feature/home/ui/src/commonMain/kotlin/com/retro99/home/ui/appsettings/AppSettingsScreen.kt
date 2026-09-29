package com.retro99.home.ui.appsettings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.buildconfig.BuildConfig
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberChevron
import com.retro99.base.ui.compose.EmberColors
import com.retro99.base.ui.compose.EmberGroupCard
import com.retro99.base.ui.compose.EmberMode
import com.retro99.base.ui.compose.EmberRowDivider
import com.retro99.base.ui.compose.EmberSectionHeader
import com.retro99.base.ui.compose.EmberSettingRow
import com.retro99.base.ui.compose.EmberSwitchRow
import com.retro99.base.ui.compose.ThemeMode
import com.retro99.base.ui.compose.colors
import com.retro99.translations.StringRes
import com.retro99.user.api.UserProfile
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import resources.translations.action_delete
import resources.translations.action_edit
import resources.translations.action_rename
import resources.translations.app_settings_clear_current_book
import resources.translations.app_settings_clear_current_book_description
import resources.translations.app_settings_current_book_clear_failed
import resources.translations.app_settings_current_book_cleared
import resources.translations.app_settings_profile_active
import resources.translations.app_settings_profile_duplicate_index
import resources.translations.app_settings_profile_operation_failed
import resources.translations.app_settings_theme_day
import resources.translations.app_settings_theme_eink
import resources.translations.app_settings_theme_night
import resources.translations.app_settings_title
import resources.translations.app_settings_version
import resources.translations.settings_app_name
import resources.translations.settings_continue_reading_subtitle
import resources.translations.settings_continue_reading_title
import resources.translations.settings_diagnostics_subtitle
import resources.translations.settings_diagnostics_title
import resources.translations.settings_open_last_book_subtitle
import resources.translations.settings_profile_add
import resources.translations.settings_profile_edit_button
import resources.translations.settings_reader_settings_subtitle
import resources.translations.settings_section_appearance
import resources.translations.settings_section_help
import resources.translations.settings_section_library_sync
import resources.translations.settings_section_profile
import resources.translations.settings_section_reading
import resources.translations.settings_servers_subtitle_default
import resources.translations.settings_status_off
import resources.translations.settings_status_on
import resources.translations.settings_sync_backup_subtitle
import resources.translations.settings_theme_auto
import resources.translations.settings_theme_auto_description
import resources.translations.settings_theme_day_description
import resources.translations.settings_theme_eink_description
import resources.translations.settings_theme_night_description
import resources.translations.settings_theme_title
import resources.translations.app_settings_open_last_book
import resources.translations.app_settings_reader_settings
import resources.translations.app_settings_servers
import resources.translations.app_settings_sync_backup

private val PREVIEW_COVER_COLOR = Color(0xFF3D4A2E)
private val AVATAR_SIZE = 56.dp

@Composable
fun AppSettingsScreen(
    onNavigateToServerManagement: () -> Unit,
    onNavigateToSyncAndBackup: () -> Unit,
    onNavigateToReaderSettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppSettingsViewModel = koinViewModel(),
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        AppSettingsScreenContent(
            viewState = viewState,
            onNavigateToServerManagement = onNavigateToServerManagement,
            onNavigateToSyncAndBackup = onNavigateToSyncAndBackup,
            onNavigateToReaderSettings = onNavigateToReaderSettings,
            onNavigateToDiagnostics = onNavigateToDiagnostics,
            intentDispatcher = intentDispatcher,
        )
    }
}

@Composable
private fun AppSettingsScreenContent(
    viewState: AppSettingsViewState,
    onNavigateToServerManagement: () -> Unit,
    onNavigateToSyncAndBackup: () -> Unit,
    onNavigateToReaderSettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    intentDispatcher: IntentDispatcher<AppSettingsIntent>,
    modifier: Modifier = Modifier,
    buildConfig: BuildConfig = koinInject(),
) {
    val colors = Ember.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val currentBookClearedMessage = stringResource(StringRes.app_settings_current_book_cleared)
    val currentBookClearFailedMessage = stringResource(StringRes.app_settings_current_book_clear_failed)
    val profileOperationFailedMessage = stringResource(StringRes.app_settings_profile_operation_failed)

    LaunchedEffect(viewState.showCurrentBookClearedMessage) {
        if (viewState.showCurrentBookClearedMessage) {
            snackbarHostState.showSnackbar(currentBookClearedMessage)
            intentDispatcher(AppSettingsIntent.OnCurrentBookClearedMessageShown)
        }
    }

    LaunchedEffect(viewState.showCurrentBookClearFailedMessage) {
        if (viewState.showCurrentBookClearFailedMessage) {
            snackbarHostState.showSnackbar(currentBookClearFailedMessage)
            intentDispatcher(AppSettingsIntent.OnCurrentBookClearFailedMessageShown)
        }
    }

    LaunchedEffect(
        viewState.showProfileOperationFailedMessage,
        viewState.showAddProfileDialog,
        viewState.showDeleteProfileDialog,
    ) {
        val dialogVisible = viewState.showAddProfileDialog ||
            viewState.showDeleteProfileDialog
        if (viewState.showProfileOperationFailedMessage && !dialogVisible) {
            snackbarHostState.showSnackbar(profileOperationFailedMessage)
            intentDispatcher(AppSettingsIntent.OnProfileOperationFailedMessageShown)
        }
    }

    ProfileDialogs(viewState = viewState, intentDispatcher = intentDispatcher)

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
            Text(
                text = stringResource(StringRes.app_settings_title),
                style = Ember.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp),
            )

            EmberSectionHeader(text = stringResource(StringRes.settings_section_profile))
            ProfileCard(viewState = viewState, intentDispatcher = intentDispatcher)
            if (
                viewState.isProfileOperationInProgress &&
                !viewState.showAddProfileDialog &&
                !viewState.showDeleteProfileDialog
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    color = colors.accent,
                    trackColor = colors.track,
                )
            }

            EmberSectionHeader(text = stringResource(StringRes.settings_section_appearance))
            EmberGroupCard {
                ThemeSelector(
                    selected = viewState.themeMode,
                    onSelected = { themeMode ->
                        intentDispatcher(AppSettingsIntent.OnThemeModeSelected(themeMode))
                    },
                )
            }

            EmberSectionHeader(text = stringResource(StringRes.settings_section_reading))
            EmberGroupCard {
                EmberSettingRow(
                    title = stringResource(StringRes.app_settings_reader_settings),
                    subtitle = stringResource(StringRes.settings_reader_settings_subtitle),
                    icon = Icons.Outlined.Tune,
                    onClick = onNavigateToReaderSettings,
                    trailing = { EmberChevron() },
                )
                EmberRowDivider()
                EmberSwitchRow(
                    title = stringResource(StringRes.app_settings_open_last_book),
                    subtitle = stringResource(StringRes.settings_open_last_book_subtitle),
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    checked = viewState.openLastBookOnLaunch,
                    onCheckedChange = { enabled ->
                        intentDispatcher(AppSettingsIntent.OnOpenLastBookToggled(enabled))
                    },
                )
                EmberRowDivider()
                EmberSwitchRow(
                    title = stringResource(StringRes.settings_continue_reading_title),
                    subtitle = stringResource(StringRes.settings_continue_reading_subtitle),
                    icon = Icons.Outlined.PlayArrow,
                    checked = viewState.showContinueReading,
                    onCheckedChange = { enabled ->
                        intentDispatcher(AppSettingsIntent.OnShowContinueReadingToggled(enabled))
                    },
                )
                if (viewState.hasCurrentlyReadingBook) {
                    EmberRowDivider()
                    EmberSettingRow(
                        title = stringResource(StringRes.app_settings_clear_current_book),
                        subtitle = stringResource(StringRes.app_settings_clear_current_book_description),
                        icon = Icons.Outlined.DeleteSweep,
                        isDestructive = true,
                        onClick = { intentDispatcher(AppSettingsIntent.OnClearCurrentBookClicked) },
                    )
                }
            }

            EmberSectionHeader(text = stringResource(StringRes.settings_section_library_sync))
            EmberGroupCard {
                EmberSettingRow(
                    title = stringResource(StringRes.app_settings_servers),
                    subtitle = if (viewState.serverNames.isEmpty()) {
                        stringResource(StringRes.settings_servers_subtitle_default)
                    } else {
                        viewState.serverNames.joinToString(", ")
                    },
                    icon = Icons.Outlined.Dns,
                    onClick = onNavigateToServerManagement,
                    trailing = { EmberChevron() },
                )
                EmberRowDivider()
                EmberSettingRow(
                    title = stringResource(StringRes.app_settings_sync_backup),
                    subtitle = stringResource(StringRes.settings_sync_backup_subtitle),
                    icon = Icons.Outlined.Cloud,
                    onClick = onNavigateToSyncAndBackup,
                    trailing = { EmberChevron() },
                )
            }

            EmberSectionHeader(text = stringResource(StringRes.settings_section_help))
            EmberGroupCard {
                EmberSettingRow(
                    title = stringResource(StringRes.settings_diagnostics_title),
                    subtitle = stringResource(StringRes.settings_diagnostics_subtitle),
                    icon = Icons.Outlined.Description,
                    onClick = onNavigateToDiagnostics,
                    trailing = {
                        Text(
                            text = stringResource(
                                if (viewState.isLoggingEnabled) {
                                    StringRes.settings_status_on
                                } else {
                                    StringRes.settings_status_off
                                },
                            ),
                            style = Ember.type.meta.copy(fontSize = 14.sp),
                            color = colors.ink2,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        EmberChevron()
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(StringRes.settings_app_name),
                    style = Ember.type.author.copy(fontSize = 15.sp),
                    color = colors.ink2,
                )
                Text(
                    text = stringResource(
                        StringRes.app_settings_version,
                        buildConfig.versionName,
                        buildConfig.versionCode,
                    ),
                    style = Ember.type.meta.copy(fontSize = 12.sp),
                    color = colors.ink2,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun ProfileDialogs(
    viewState: AppSettingsViewState,
    intentDispatcher: IntentDispatcher<AppSettingsIntent>,
) {
    if (viewState.showAddProfileDialog) {
        AddProfileDialog(
            onDismissRequest = {
                intentDispatcher(AppSettingsIntent.OnAddProfileDismissed("dismiss_request"))
            },
            onCancel = {
                intentDispatcher(AppSettingsIntent.OnAddProfileDismissed("cancel_button"))
            },
            onConfirm = { name -> intentDispatcher(AppSettingsIntent.OnAddProfileConfirmed(name)) },
            onNameChanged = { intentDispatcher(AppSettingsIntent.OnProfileNameEdited) },
            showError = viewState.showProfileOperationFailedMessage,
            showDuplicateNameError = viewState.showDuplicateProfileNameError,
            isOperationInProgress = viewState.isProfileOperationInProgress,
        )
    }

    val selectedProfile = viewState.selectedProfileForMenu
    if (selectedProfile != null) {
        ProfileEditSheet(
            profile = selectedProfile,
            canDelete = viewState.canDeleteSelectedProfile,
            showDuplicateNameError = viewState.showDuplicateProfileNameError,
            isBusy = viewState.isProfileOperationInProgress,
            onSave = { name, colorIndex ->
                intentDispatcher(AppSettingsIntent.OnEditProfileSaved(name, colorIndex))
            },
            onNameChanged = { intentDispatcher(AppSettingsIntent.OnProfileNameEdited) },
            onDelete = { intentDispatcher(AppSettingsIntent.OnDeleteProfileClicked) },
            onDismiss = {
                intentDispatcher(AppSettingsIntent.OnProfileMenuDismissed("dismiss_request"))
            },
        )
    }

    if (viewState.showDeleteProfileDialog && selectedProfile != null) {
        DeleteProfileConfirmationDialog(
            profileName = selectedProfile.name,
            onDismissRequest = {
                intentDispatcher(AppSettingsIntent.OnDeleteProfileDismissed("dismiss_request"))
            },
            onCancel = {
                intentDispatcher(AppSettingsIntent.OnDeleteProfileDismissed("cancel_button"))
            },
            onConfirm = { intentDispatcher(AppSettingsIntent.OnDeleteProfileConfirmed) },
            showError = viewState.showProfileOperationFailedMessage,
            isOperationInProgress = viewState.isProfileOperationInProgress,
        )
    }
}

@Composable
private fun ProfileCard(
    viewState: AppSettingsViewState,
    intentDispatcher: IntentDispatcher<AppSettingsIntent>,
) {
    val duplicateOrdinals = remember(viewState.userProfiles) {
        duplicateProfileOrdinals(viewState.userProfiles)
    }
    val enabled = !viewState.isProfileOperationInProgress
    val activeProfileId = viewState.activeProfile?.id

    EmberGroupCard {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 116.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(viewState.userProfiles, key = { profile -> profile.id }) { profile ->
                    val isActive = profile.id == activeProfileId
                    ProfileAvatar(
                        profile = profile,
                        duplicateOrdinal = duplicateOrdinals[profile.id],
                        isActive = isActive,
                        enabled = enabled,
                        onClick = {
                            if (isActive) {
                                intentDispatcher(AppSettingsIntent.OnProfileLongPressed(profile.id, "active_avatar"))
                            } else {
                                intentDispatcher(AppSettingsIntent.OnProfileSelected(profile.id))
                            }
                        },
                        onLongClick = {
                            intentDispatcher(AppSettingsIntent.OnProfileLongPressed(profile.id, "long_press"))
                        },
                    )
                }
                item(key = "add_profile") {
                    AddProfileAvatar(
                        enabled = enabled,
                        onClick = { intentDispatcher(AppSettingsIntent.OnAddProfileClicked) },
                    )
                }
            }

            if (activeProfileId != null) {
                EditProfileButton(
                    enabled = enabled,
                    onClick = {
                        intentDispatcher(AppSettingsIntent.OnProfileLongPressed(activeProfileId, "edit_button"))
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun EditProfileButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style

    Row(
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .border(style.border, colors.chipBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.accentText,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(StringRes.settings_profile_edit_button),
            style = Ember.type.label.copy(fontSize = 14.sp),
            color = colors.accentText,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileAvatar(
    profile: UserProfile,
    duplicateOrdinal: Int?,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Ember.colors
    val activeLabel = stringResource(StringRes.app_settings_profile_active)

    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(AVATAR_SIZE)) {
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(profileAvatarColor(profile.avatarId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.name.firstOrNull()?.uppercase().orEmpty(),
                    style = Ember.type.cardTitle.copy(fontSize = 22.sp),
                    color = ProfileAvatarContentColor,
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(22.dp)
                        .border(2.dp, colors.surface, CircleShape)
                        .padding(2.dp)
                        .background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = activeLabel,
                        modifier = Modifier.size(12.dp),
                        tint = colors.onAccent,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = profile.name,
            style = Ember.type.meta.copy(
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (isActive) colors.ink else colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        duplicateOrdinal?.let { ordinal ->
            Text(
                text = stringResource(StringRes.app_settings_profile_duplicate_index, ordinal),
                style = Ember.type.meta.copy(fontSize = 11.sp),
                color = colors.ink2,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AddProfileAvatar(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    val label = stringResource(StringRes.settings_profile_add)

    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = false, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .drawBehind {
                    drawCircle(
                        color = colors.ink2,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
                tint = colors.ink2,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = Ember.type.meta,
            color = colors.ink2,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** Four preview tiles (Night, Day, E-ink, Auto) with a description of the selected one. */
@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    val colors = Ember.colors
    val tiles = listOf(ThemeMode.Night, ThemeMode.Day, ThemeMode.Eink, ThemeMode.System)

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(StringRes.settings_theme_title),
            style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = colors.ink,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tiles.forEach { themeMode ->
                ThemeTile(
                    themeMode = themeMode,
                    selected = themeMode == selected,
                    onClick = { onSelected(themeMode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = stringResource(selected.descriptionRes()),
            style = Ember.type.meta,
            color = colors.ink2,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ThemeTile(
    themeMode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(14.dp)
    val borderWidth = when {
        selected -> 2.5.dp
        style.isEink -> style.border
        else -> 1.dp
    }
    val borderColor = if (selected) colors.accent else colors.line

    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(shape)
                .border(borderWidth, borderColor, shape)
                .drawBehind { drawThemePreview(themeMode) },
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.ink,
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
            Text(
                text = stringResource(themeMode.labelRes()),
                style = Ember.type.meta.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                ),
                color = if (selected) colors.ink else colors.ink2,
                maxLines = 1,
            )
        }
    }
}

private fun ThemeMode.labelRes(): StringResource = when (this) {
    ThemeMode.Night -> StringRes.app_settings_theme_night
    ThemeMode.Day -> StringRes.app_settings_theme_day
    ThemeMode.Eink -> StringRes.app_settings_theme_eink
    ThemeMode.System -> StringRes.settings_theme_auto
}

private fun ThemeMode.descriptionRes(): StringResource = when (this) {
    ThemeMode.Night -> StringRes.settings_theme_night_description
    ThemeMode.Day -> StringRes.settings_theme_day_description
    ThemeMode.Eink -> StringRes.settings_theme_eink_description
    ThemeMode.System -> StringRes.settings_theme_auto_description
}

/** Draws a tiny library screen in the previewed theme. Auto splits Day and Night diagonally. */
private fun DrawScope.drawThemePreview(themeMode: ThemeMode) {
    when (themeMode) {
        ThemeMode.Night -> drawLibraryPreview(EmberMode.Night.colors())
        ThemeMode.Day -> drawLibraryPreview(EmberMode.Day.colors())
        ThemeMode.Eink -> drawLibraryPreview(EmberMode.Eink.colors())
        ThemeMode.System -> {
            drawLibraryPreview(EmberMode.Day.colors())
            val night = Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            clipPath(night) { drawLibraryPreview(EmberMode.Night.colors()) }
        }
    }
}

private fun DrawScope.drawLibraryPreview(colors: EmberColors) {
    val w = size.width
    val h = size.height
    val eink = colors.accent == Color.Black
    drawRect(color = colors.bg)

    // Title bar
    drawRoundRect(
        color = colors.ink,
        topLeft = Offset(w * 0.14f, h * 0.13f),
        size = Size(w * 0.38f, h * 0.08f),
        cornerRadius = CornerRadius(h * 0.04f),
    )
    // Cover
    val coverTopLeft = Offset(w * 0.14f, h * 0.30f)
    val coverSize = Size(w * 0.24f, h * 0.36f)
    if (eink) {
        drawRoundRect(color = colors.bg, topLeft = coverTopLeft, size = coverSize, cornerRadius = CornerRadius(4f))
        drawRoundRect(
            color = colors.ink,
            topLeft = coverTopLeft,
            size = coverSize,
            cornerRadius = CornerRadius(4f),
            style = Stroke(width = 3f),
        )
    } else {
        drawRoundRect(color = PREVIEW_COVER_COLOR, topLeft = coverTopLeft, size = coverSize, cornerRadius = CornerRadius(4f))
    }
    // Text lines beside the cover
    drawRoundRect(
        color = colors.ink,
        topLeft = Offset(w * 0.44f, h * 0.31f),
        size = Size(w * 0.42f, h * 0.06f),
        cornerRadius = CornerRadius(h * 0.03f),
    )
    drawRoundRect(
        color = colors.ink2,
        topLeft = Offset(w * 0.44f, h * 0.43f),
        size = Size(w * 0.28f, h * 0.05f),
        cornerRadius = CornerRadius(h * 0.025f),
    )
    drawRoundRect(
        color = colors.accent,
        topLeft = Offset(w * 0.44f, h * 0.55f),
        size = Size(w * 0.34f, h * 0.05f),
        cornerRadius = CornerRadius(h * 0.025f),
    )
    // List rows
    drawRoundRect(
        color = colors.ink2.copy(alpha = 0.7f),
        topLeft = Offset(w * 0.14f, h * 0.76f),
        size = Size(w * 0.72f, h * 0.05f),
        cornerRadius = CornerRadius(h * 0.025f),
    )
    drawRoundRect(
        color = colors.ink2.copy(alpha = 0.5f),
        topLeft = Offset(w * 0.14f, h * 0.86f),
        size = Size(w * 0.5f, h * 0.05f),
        cornerRadius = CornerRadius(h * 0.025f),
    )
}
