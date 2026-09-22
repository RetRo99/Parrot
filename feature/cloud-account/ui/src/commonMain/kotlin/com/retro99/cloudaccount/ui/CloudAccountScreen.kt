package com.retro99.cloudaccount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import resources.translations.cloud_account_sync_status_action_required
import resources.translations.cloud_account_sync_status_failed
import resources.translations.cloud_account_sync_status_pending
import resources.translations.cloud_account_sync_status_synchronizing
import resources.translations.cloud_account_sync_status_up_to_date
import resources.translations.cloud_account_title
import resources.translations.cloud_account_verification_message
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
                        onSignOut = {
                            intentDispatcher(CloudAccountIntent.OnSignOutClicked)
                        },
                        onSync = {
                            intentDispatcher(CloudAccountIntent.OnSyncClicked)
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
    onSignOut: () -> Unit,
    onSync: () -> Unit,
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
    }
}

@Composable
private fun SyncStatusMessage(
    status: SyncStatus,
    modifier: Modifier = Modifier,
) {
    val message = when (status) {
        SyncStatus.Idle -> return
        is SyncStatus.Synchronizing -> stringResource(
            StringRes.cloud_account_sync_status_synchronizing,
        )
        SyncStatus.UpToDate -> stringResource(StringRes.cloud_account_sync_status_up_to_date)
        is SyncStatus.Pending -> stringResource(StringRes.cloud_account_sync_status_pending)
        is SyncStatus.ActionRequired -> stringResource(
            StringRes.cloud_account_sync_status_action_required,
        )
        is SyncStatus.Failed -> stringResource(StringRes.cloud_account_sync_status_failed)
    }
    StatusMessage(message = message, modifier = modifier)
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
