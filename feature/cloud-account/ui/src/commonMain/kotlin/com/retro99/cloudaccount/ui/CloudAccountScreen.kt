package com.retro99.cloudaccount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.CloudStorageUsage
import com.retro99.sync.domain.SyncPhase
import com.retro99.sync.domain.SyncStatus
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.app_settings_sync_backup
import resources.translations.cloud_account_check_email
import resources.translations.cloud_account_connected
import resources.translations.cloud_account_create_account
import resources.translations.cloud_account_description
import resources.translations.cloud_account_delete
import resources.translations.cloud_account_delete_confirm
import resources.translations.cloud_account_delete_message
import resources.translations.cloud_account_delete_title
import resources.translations.cloud_account_email_label
import resources.translations.cloud_account_enable_sync
import resources.translations.cloud_account_generic_error
import resources.translations.cloud_account_link_pending
import resources.translations.cloud_account_link_profile
import resources.translations.cloud_account_link_profile_description
import resources.translations.cloud_account_link_profile_title
import resources.translations.cloud_account_not_configured
import resources.translations.cloud_account_password_label
import resources.translations.cloud_account_profile_already_linked
import resources.translations.cloud_account_reauthentication_required
import resources.translations.cloud_account_refresh_unavailable
import resources.translations.cloud_account_sign_in
import resources.translations.cloud_account_sign_in_with_google
import resources.translations.cloud_account_sign_out
import resources.translations.cloud_account_switch_to_create
import resources.translations.cloud_account_switch_to_sign_in
import resources.translations.cloud_account_sync_enabled
import resources.translations.cloud_account_sync_not_enabled
import resources.translations.cloud_account_sync_now
import resources.translations.cloud_account_sync_status_applying
import resources.translations.cloud_account_sync_status_bytes_progress
import resources.translations.cloud_account_sync_status_can_retry
import resources.translations.cloud_account_sync_status_cannot_retry
import resources.translations.cloud_account_sync_status_completed
import resources.translations.cloud_account_sync_status_disabled
import resources.translations.cloud_account_sync_status_failed
import resources.translations.cloud_account_sync_status_downloading_files
import resources.translations.cloud_account_sync_status_finalizing
import resources.translations.cloud_account_sync_status_idle
import resources.translations.cloud_account_sync_status_items_progress
import resources.translations.cloud_account_sync_status_last_successful
import resources.translations.cloud_account_sync_status_offline
import resources.translations.cloud_account_sync_status_pulling
import resources.translations.cloud_account_sync_status_preparing
import resources.translations.cloud_account_sync_status_uploading_changes
import resources.translations.cloud_account_sync_status_uploading_files
import resources.translations.cloud_account_sync_status_transferring_files
import resources.translations.cloud_account_title
import resources.translations.cloud_account_verification_message
import resources.translations.cloud_backup_attestation_checkbox
import resources.translations.cloud_backup_autobackup_confirm_body
import resources.translations.cloud_backup_autobackup_enable
import resources.translations.cloud_backup_autobackup_not_now
import resources.translations.cloud_backup_autobackup_toggle
import resources.translations.cloud_storage_usage_title
import resources.translations.cloud_storage_usage_used
import resources.translations.cloud_storage_usage_reserved
import resources.translations.cloud_storage_usage_error
import resources.translations.general_back
import resources.translations.general_cancel
import resources.translations.login_hide_password
import resources.translations.login_show_password

@Composable
fun CloudAccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CloudAccountViewModel = koinViewModel { parametersOf(onBack) },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        CloudAccountScreenContent(
            viewState = viewState,
            emailState = viewModel.emailState,
            passwordState = viewModel.passwordState,
            intentDispatcher = intentDispatcher,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudAccountScreenContent(
    viewState: CloudAccountViewState,
    emailState: TextFieldState,
    passwordState: TextFieldState,
    intentDispatcher: IntentDispatcher<CloudAccountIntent>,
    modifier: Modifier = Modifier,
) {
    if (viewState.showLinkConfirmation) {
        LinkProfileConfirmationDialog(
            onConfirm = { intentDispatcher(CloudAccountIntent.OnLinkConfirmed) },
            onDismiss = { intentDispatcher(CloudAccountIntent.OnLinkDismissed) },
        )
    }
    if (viewState.showDeleteAccountConfirmation) {
        DeleteCloudAccountConfirmationDialog(
            onConfirm = { intentDispatcher(CloudAccountIntent.OnDeleteAccountConfirmed) },
            onDismiss = { intentDispatcher(CloudAccountIntent.OnDeleteAccountDismissed) },
        )
    }
    if (viewState.showAutoBackupConfirmation) {
        AutoBackupConfirmationDialog(
            rightsAttested = viewState.autoBackupRightsAttested,
            isUpdating = viewState.isUpdatingAutoBackup,
            onRightsAttestedChanged = {
                intentDispatcher(CloudAccountIntent.OnAutoBackupAttestationChanged(it))
            },
            onConfirm = { intentDispatcher(CloudAccountIntent.OnAutoBackupConfirmed) },
            onDismiss = { intentDispatcher(CloudAccountIntent.OnAutoBackupDismissed) },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(StringRes.app_settings_sync_backup)) },
                navigationIcon = {
                    IconButton(onClick = { intentDispatcher(CloudAccountIntent.OnBackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(StringRes.general_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(StringRes.cloud_account_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(StringRes.cloud_account_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))

            when (val authState = viewState.authState) {
                CloudAuthState.RestoringSession -> {
                    CircularProgressIndicator()
                }
                is CloudAuthState.SignedIn -> {
                    ConnectedAccountContent(
                        account = authState.account,
                        profileLink = viewState.profileLink,
                        syncStatus = viewState.syncStatus,
                        isLoading = viewState.isLoading,
                        error = viewState.error,
                        storageUsage = viewState.storageUsage,
                        isLoadingStorageUsage = viewState.isLoadingStorageUsage,
                        storageUsageError = viewState.storageUsageError,
                        isUpdatingAutoBackup = viewState.isUpdatingAutoBackup,
                        onSignOut = {
                            intentDispatcher(CloudAccountIntent.OnSignOutClicked)
                        },
                        onDeleteAccount = {
                            intentDispatcher(CloudAccountIntent.OnDeleteAccountClicked)
                        },
                        onSync = {
                            intentDispatcher(CloudAccountIntent.OnSyncClicked)
                        },
                        onAutoBackupToggled = { enabled ->
                            intentDispatcher(CloudAccountIntent.OnAutoBackupToggled(enabled))
                        },
                    )
                }
                else -> {
                    AccountFormContent(
                        authState = authState,
                        viewState = viewState,
                        emailState = emailState,
                        passwordState = passwordState,
                        intentDispatcher = intentDispatcher,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkProfileConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(StringRes.cloud_account_link_profile_title))
        },
        text = {
            Text(stringResource(StringRes.cloud_account_link_profile_description))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(StringRes.cloud_account_link_profile))
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
private fun DeleteCloudAccountConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(StringRes.cloud_account_delete_title)) },
        text = { Text(stringResource(StringRes.cloud_account_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(StringRes.cloud_account_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
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
private fun AutoBackupConfirmationDialog(
    rightsAttested: Boolean,
    isUpdating: Boolean,
    onRightsAttestedChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(StringRes.cloud_backup_autobackup_confirm_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rightsAttested,
                        onCheckedChange = onRightsAttestedChanged,
                        enabled = !isUpdating,
                    )
                    Text(stringResource(StringRes.cloud_backup_attestation_checkbox))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = rightsAttested && !isUpdating,
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(StringRes.cloud_backup_autobackup_enable))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUpdating) {
                Text(stringResource(StringRes.cloud_backup_autobackup_not_now))
            }
        },
    )
}

@Composable
private fun AccountFormContent(
    authState: CloudAuthState,
    viewState: CloudAccountViewState,
    emailState: TextFieldState,
    passwordState: TextFieldState,
    intentDispatcher: IntentDispatcher<CloudAccountIntent>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AuthStateMessage(authState = authState)

        if (viewState.showVerificationMessage && authState is CloudAuthState.AwaitingEmailVerification) {
            Text(
                text = stringResource(StringRes.cloud_account_check_email),
                style = MaterialTheme.typography.titleMedium,
            )
            StatusMessage(
                message = stringResource(
                    StringRes.cloud_account_verification_message,
                    authState.email,
                ),
            )
        }

        viewState.error?.let { error ->
            ErrorMessage(error = error)
        }

        OutlinedTextField(
            state = emailState,
            enabled = !viewState.isLoading,
            label = { Text(stringResource(StringRes.cloud_account_email_label)) },
            modifier = Modifier.fillMaxWidth(),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
        )

        PasswordField(
            passwordState = passwordState,
            enabled = !viewState.isLoading,
        )

        if (viewState.mode == CloudAccountMode.CreateAccount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = viewState.tosAccepted,
                    onCheckedChange = { accepted ->
                        intentDispatcher(CloudAccountIntent.OnTosAcceptedChanged(accepted))
                    },
                    enabled = !viewState.isLoading,
                )
                Text(
                    text = stringResource(StringRes.cloud_account_tos_checkbox),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(
            onClick = { intentDispatcher(CloudAccountIntent.OnSubmitClicked) },
            enabled = viewState.isSubmitEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (viewState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(
                        if (viewState.mode == CloudAccountMode.SignIn) {
                            StringRes.cloud_account_sign_in
                        } else {
                            StringRes.cloud_account_create_account
                        },
                    ),
                )
            }
        }

        OutlinedButton(
            onClick = {
                intentDispatcher(CloudAccountIntent.OnGoogleSignInClicked)
            },
            enabled = !viewState.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(StringRes.cloud_account_sign_in_with_google))
        }

        TextButton(
            onClick = {
                intentDispatcher(
                    if (viewState.mode == CloudAccountMode.SignIn) {
                        CloudAccountIntent.OnSwitchToCreateAccountClicked
                    } else {
                        CloudAccountIntent.OnSwitchToSignInClicked
                    },
                )
            },
            enabled = !viewState.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (viewState.mode == CloudAccountMode.SignIn) {
                        StringRes.cloud_account_switch_to_create
                    } else {
                        StringRes.cloud_account_switch_to_sign_in
                    },
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PasswordField(
    passwordState: TextFieldState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = passwordState,
        label = { Text(stringResource(StringRes.cloud_account_password_label)) },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        textObfuscationMode = if (passwordVisible) {
            TextObfuscationMode.Visible
        } else {
            TextObfuscationMode.Hidden
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = stringResource(
                        if (passwordVisible) {
                            StringRes.login_hide_password
                        } else {
                            StringRes.login_show_password
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun ConnectedAccountContent(
    account: CloudAccount,
    profileLink: CloudProfileLink?,
    syncStatus: SyncStatus,
    isLoading: Boolean,
    error: CloudAccountError?,
    storageUsage: CloudStorageUsage?,
    isLoadingStorageUsage: Boolean,
    storageUsageError: String?,
    isUpdatingAutoBackup: Boolean,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSync: () -> Unit,
    onAutoBackupToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { accountError ->
            ErrorMessage(error = accountError)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(StringRes.cloud_account_connected),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = account.email ?: account.id,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when {
                        profileLink == null -> stringResource(StringRes.cloud_account_link_pending)
                        profileLink.syncEnabled -> stringResource(StringRes.cloud_account_sync_enabled)
                        else -> stringResource(StringRes.cloud_account_sync_not_enabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                SyncStatusMessage(status = syncStatus)
                if (profileLink != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(StringRes.cloud_backup_autobackup_toggle),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = profileLink.autoBackupEnabled,
                            onCheckedChange = onAutoBackupToggled,
                            enabled = !isLoading && !isUpdatingAutoBackup,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onSync,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                stringResource(
                                    if (profileLink.syncEnabled) {
                                        StringRes.cloud_account_sync_now
                                    } else {
                                        StringRes.cloud_account_enable_sync
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }

        StorageUsageCard(
            usage = storageUsage,
            isLoading = isLoadingStorageUsage,
            error = storageUsageError,
        )

        Button(
            onClick = onSignOut,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(StringRes.cloud_account_sign_out))
            }
        }

        OutlinedButton(
            onClick = onDeleteAccount,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(StringRes.cloud_account_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StorageUsageCard(
    usage: CloudStorageUsage?,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(StringRes.cloud_storage_usage_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                isLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                usage != null -> {
                    val progress = if (usage.quotaBytes > 0) {
                        (usage.usedBytes.toFloat() / usage.quotaBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Text(
                        text = stringResource(
                            StringRes.cloud_storage_usage_used,
                            usage.usedBytes.toStorageLabel(),
                            usage.quotaBytes.toStorageLabel(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (usage.reservedBytes > 0L) {
                        Text(
                            stringResource(
                                StringRes.cloud_storage_usage_reserved,
                                usage.reservedBytes.toStorageLabel(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error != null -> Text(
                    stringResource(StringRes.cloud_storage_usage_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun Long.toStorageLabel(): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = this.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val whole = value.toLong()
    val tenth = ((value - whole) * 10).toInt()
    return if (unitIndex == 0) "$whole ${units[unitIndex]}" else "$whole.$tenth ${units[unitIndex]}"
}

@Composable
private fun SyncStatusMessage(
    status: SyncStatus,
    modifier: Modifier = Modifier,
) {
    val message = when (status) {
        SyncStatus.Disabled -> stringResource(StringRes.cloud_account_sync_status_disabled)
        is SyncStatus.Idle -> {
            val ready = stringResource(StringRes.cloud_account_sync_status_idle)
            status.lastSuccessfulAt?.let { lastSuccessfulAt ->
                "$ready\n${stringResource(StringRes.cloud_account_sync_status_last_successful, lastSuccessfulAt)}"
            } ?: ready
        }
        is SyncStatus.Running -> stringResource(
            when (status.phase) {
                SyncPhase.PREPARING -> StringRes.cloud_account_sync_status_preparing
                SyncPhase.PULLING -> StringRes.cloud_account_sync_status_pulling
                SyncPhase.APPLYING -> StringRes.cloud_account_sync_status_applying
                SyncPhase.UPLOADING_CHANGES -> StringRes.cloud_account_sync_status_uploading_changes
                SyncPhase.UPLOADING_FILES -> StringRes.cloud_account_sync_status_uploading_files
                SyncPhase.DOWNLOADING_FILES -> StringRes.cloud_account_sync_status_downloading_files
                SyncPhase.TRANSFERRING_FILES -> StringRes.cloud_account_sync_status_transferring_files
                SyncPhase.FINALIZING -> StringRes.cloud_account_sync_status_finalizing
            },
        )
        is SyncStatus.Offline -> stringResource(
            StringRes.cloud_account_sync_status_offline,
            status.pendingCount,
        )
        is SyncStatus.Failed -> {
            val failure = stringResource(
                StringRes.cloud_account_sync_status_failed,
                status.error,
                status.pendingCount,
            )
            val retryMessage = stringResource(
                if (status.canRetry) {
                    StringRes.cloud_account_sync_status_can_retry
                } else {
                    StringRes.cloud_account_sync_status_cannot_retry
                },
            )
            "$failure\n$retryMessage"
        }
        is SyncStatus.Completed -> {
            val completed = stringResource(
                StringRes.cloud_account_sync_status_completed,
                status.pushedCount,
                status.pulledCount,
                status.pendingCount,
            )
            "$completed\n${stringResource(StringRes.cloud_account_sync_status_last_successful, status.completedAt)}"
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusMessage(message = message)
        if (status is SyncStatus.Running) {
            val totalItems = status.totalItems
            val totalBytes = status.totalBytes
            val itemFraction = totalItems?.takeIf { total -> total > 0 }?.let { total ->
                (status.completedItems.toFloat() / total).coerceIn(0f, 1f)
            }
            val byteFraction = totalBytes?.takeIf { total -> total > 0L }?.let { total ->
                (status.bytesTransferred.toFloat() / total).coerceIn(0f, 1f)
            }
            if (totalItems != null) {
                Text(
                    text = stringResource(
                        StringRes.cloud_account_sync_status_items_progress,
                        status.completedItems,
                        totalItems,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (totalBytes != null) {
                Text(
                    text = stringResource(
                        StringRes.cloud_account_sync_status_bytes_progress,
                        status.bytesTransferred,
                        totalBytes,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                byteFraction != null -> LinearProgressIndicator(
                    progress = { byteFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                itemFraction != null -> LinearProgressIndicator(
                    progress = { itemFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AuthStateMessage(
    authState: CloudAuthState,
    modifier: Modifier = Modifier,
) {
    when (authState) {
        is CloudAuthState.ReauthenticationRequired -> StatusMessage(
            message = stringResource(StringRes.cloud_account_reauthentication_required),
            modifier = modifier,
        )
        is CloudAuthState.RefreshUnavailable -> StatusMessage(
            message = stringResource(StringRes.cloud_account_refresh_unavailable),
            modifier = modifier,
        )
        else -> Unit
    }
}

@Composable
private fun StatusMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ErrorMessage(
    error: CloudAccountError,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(error.stringRes),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val CloudAccountError.stringRes: StringResource
    get() = when (this) {
        CloudAccountError.ProfileAlreadyLinked -> StringRes.cloud_account_profile_already_linked
        CloudAccountError.NotConfigured -> StringRes.cloud_account_not_configured
        CloudAccountError.Generic -> StringRes.cloud_account_generic_error
    }
