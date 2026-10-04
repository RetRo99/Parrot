package com.retro99.cloudaccount.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberCard
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.sync.domain.SyncStatus
import com.retro99.sync.domain.SyncPhase
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.*
import kotlin.time.Clock

@Composable
fun CloudAccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialCreateAccount: Boolean = false,
    onAuthenticated: (() -> Unit)? = null,
    viewModel: CloudAccountViewModel = koinViewModel { parametersOf(onBack) },
) {
    BaseScreen(modifier = modifier, viewModel = viewModel) { state, dispatch ->
        LaunchedEffect(initialCreateAccount) {
            if (initialCreateAccount) dispatch(CloudAccountIntent.OnSwitchToCreateAccountClicked)
        }
        LaunchedEffect(state.authState, state.profileLink, state.isLoading) {
            val signedIn = state.authState as? CloudAuthState.SignedIn
            if (signedIn != null && !state.isLoading && state.profileLink?.cloudUserId == signedIn.account.id) {
                onAuthenticated?.invoke()
            }
        }
        CloudAccountScreenContent(state, viewModel.emailState, viewModel.passwordState, dispatch)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudAccountScreenContent(
    state: CloudAccountViewState,
    emailState: TextFieldState,
    passwordState: TextFieldState,
    dispatch: IntentDispatcher<CloudAccountIntent>,
) {
    val colors = Ember.colors
    CloudAccountConfirmations(state, dispatch)
    Scaffold(
        containerColor = colors.bg,
        contentColor = colors.ink,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(StringRes.cloud_account_title), style = Ember.type.screenTitle.copy(fontSize = 24.sp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg, titleContentColor = colors.ink, navigationIconContentColor = colors.ink),
                navigationIcon = {
                    IconButton(onClick = { dispatch(CloudAccountIntent.OnBackClicked) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(StringRes.general_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val auth = state.authState) {
                CloudAuthState.RestoringSession -> CloudText(stringResource(StringRes.parrot_cloud_restoring))
                is CloudAuthState.SignedIn -> ParrotCloudConnectedContent(auth.account, state, dispatch)
                else -> ParrotCloudSignedOutContent(state, emailState, passwordState, dispatch)
            }
        }
    }
}

@Composable
private fun ParrotCloudSignedOutContent(
    state: CloudAccountViewState,
    emailState: TextFieldState,
    passwordState: TextFieldState,
    dispatch: IntentDispatcher<CloudAccountIntent>,
) {
    val create = state.mode == CloudAccountMode.CreateAccount
    val emailLabel = stringResource(StringRes.cloud_account_email_label)
    val passwordLabel = stringResource(StringRes.cloud_account_password_label)
    val emailError = when {
        state.showEmailValidationError -> stringResource(StringRes.cloud_account_email_invalid)
        state.error == CloudAccountError.EmailAlreadyRegistered -> stringResource(StringRes.parrot_cloud_email_registered)
        else -> null
    }
    val passwordError = when (state.error) {
        CloudAccountError.InvalidCredentials -> stringResource(StringRes.parrot_cloud_credentials_error)
        CloudAccountError.WeakPassword -> stringResource(StringRes.parrot_cloud_weak_password)
        else -> null
    }
    EmberCard(contentPadding = 16.dp) {
        Benefit(stringResource(StringRes.parrot_cloud_benefit_books))
        Spacer(Modifier.height(10.dp))
        Benefit(stringResource(StringRes.parrot_cloud_benefit_progress))
    }
    Spacer(Modifier.height(2.dp))
    when (val auth = state.authState) {
        is CloudAuthState.AwaitingEmailVerification -> CloudNotice(stringResource(StringRes.cloud_account_verification_message, auth.email))
        is CloudAuthState.ReauthenticationRequired -> CloudNotice(stringResource(StringRes.cloud_account_reauthentication_required))
        is CloudAuthState.RefreshUnavailable -> CloudNotice(stringResource(StringRes.cloud_account_refresh_unavailable))
        else -> Unit
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EmberSectionLabel(emailLabel)
        OutlinedTextField(
            state = emailState,
            enabled = !state.isLoading,
            isError = emailError != null,
            shape = RoundedCornerShape(16.dp),
            colors = cloudFieldColors(),
            textStyle = Ember.type.meta.copy(fontSize = 15.sp),
            placeholder = { CloudText(stringResource(StringRes.parrot_cloud_email_placeholder), secondary = true) },
            modifier = Modifier.fillMaxWidth().height(52.dp).cloudFieldOutline(emailError != null)
                .onFocusChanged { dispatch(CloudAccountIntent.OnEmailFocusChanged(it.isFocused)) }
                .semantics {
                    contentDescription = emailLabel
                    emailError?.let { error(it) }
                },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        )
        emailError?.let { CloudText(it, error = true) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EmberSectionLabel(passwordLabel)
        var visible by remember { mutableStateOf(false) }
        OutlinedSecureTextField(
            state = passwordState,
            enabled = !state.isLoading,
            isError = passwordError != null,
            shape = RoundedCornerShape(16.dp),
            colors = cloudFieldColors(),
            textStyle = Ember.type.meta.copy(fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth().height(52.dp).cloudFieldOutline(passwordError != null).semantics {
                contentDescription = passwordLabel
                passwordError?.let { error(it) }
            },
            textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = {
                    visible = !visible
                    dispatch(CloudAccountIntent.OnPasswordVisibilityChanged(visible))
                }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (visible) StringRes.login_hide_password else StringRes.login_show_password),
                        tint = Ember.colors.ink2,
                    )
                }
            },
        )
        passwordError?.let { CloudText(it, error = true) }
        if (create) CloudText(stringResource(StringRes.parrot_cloud_password_helper), secondary = true)
    }
    if (create) {
        ConsentRow(stringResource(StringRes.cloud_account_tos_checkbox), state.tosAccepted, !state.isLoading) {
            dispatch(CloudAccountIntent.OnTosAcceptedChanged(it))
        }
    }
    if (state.error != null && emailError == null && passwordError == null) {
        CloudNotice(accountErrorMessage(state.error), isError = true) {
            CloudLink(stringResource(StringRes.parrot_cloud_try_again), enabled = state.isSubmitEnabled) {
                dispatch(CloudAccountIntent.OnSubmitClicked)
            }
        }
    }
    CloudButton(
        text = stringResource(if (state.isLoading) {
            if (create) StringRes.parrot_cloud_creating else StringRes.parrot_cloud_signing_in
        } else if (create) StringRes.cloud_account_create_account else StringRes.cloud_account_sign_in),
        primary = true,
        enabled = state.isSubmitEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) { dispatch(CloudAccountIntent.OnSubmitClicked) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CloudDivider(Modifier.weight(1f))
        CloudText(stringResource(StringRes.parrot_cloud_or), secondary = true)
        CloudDivider(Modifier.weight(1f))
    }
    CloudButton(stringResource(StringRes.parrot_cloud_google), enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
        dispatch(CloudAccountIntent.OnGoogleSignInClicked)
    }
    val modeHint = stringResource(if (create) StringRes.parrot_cloud_existing_account else StringRes.parrot_cloud_new_account)
    val modeAction = stringResource(if (create) StringRes.cloud_account_sign_in else StringRes.parrot_cloud_create_link)
    val colors = Ember.colors
    TextButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = !state.isLoading,
        onClick = {
            dispatch(if (create) CloudAccountIntent.OnSwitchToSignInClicked else CloudAccountIntent.OnSwitchToCreateAccountClicked)
        },
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.ink2)) { append("$modeHint ") }
                withStyle(SpanStyle(color = if (state.isLoading) colors.ink2 else colors.accentText, fontWeight = FontWeight.Bold)) { append(modeAction) }
            },
            style = Ember.type.meta.copy(fontSize = 15.sp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Benefit(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = Ember.colors.accentText, style = Ember.type.meta.copy(fontSize = 18.sp))
        CloudText(text)
    }
}

@Composable
private fun ParrotCloudConnectedContent(account: CloudAccount, state: CloudAccountViewState, dispatch: IntentDispatcher<CloudAccountIntent>) {
    ParrotCloudAccountCard(account, state)
    ParrotCloudSyncCard(state, dispatch)
    ParrotCloudStorageCard(state)
    ParrotCloudSettingsCard(state, dispatch)
    state.error?.let { CloudNotice(accountErrorMessage(it), isError = true) }
    CloudLink(
        stringResource(StringRes.parrot_cloud_delete_link),
        destructive = true,
        enabled = !state.isLoading,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) { dispatch(CloudAccountIntent.OnDeleteAccountClicked) }
}

@Composable
private fun ParrotCloudAccountCard(account: CloudAccount, state: CloudAccountViewState) {
    val email = account.email ?: stringResource(StringRes.parrot_cloud_connected_account)
    EmberCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (Ember.style.isEink) Ember.colors.surface else Ember.colors.navActive)
                    .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(email.take(1).uppercase(), style = Ember.type.cardTitle, color = Ember.colors.accentText)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(email, style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.storageUsage?.let {
                    CloudText(stringResource(StringRes.parrot_cloud_account_storage, storageLabel(it.quotaBytes)), secondary = true)
                }
            }
        }
    }
}

@Composable
private fun ParrotCloudSyncCard(state: CloudAccountViewState, dispatch: IntentDispatcher<CloudAccountIntent>) {
    val status = state.syncStatus
    val failed = status is SyncStatus.Offline || status is SyncStatus.Failed
    val synced = state.profileLink?.syncEnabled == true && state.lastSuccessfulSyncAt != null && when (status) {
        is SyncStatus.Completed -> status.pendingCount == 0
        is SyncStatus.Idle -> status.pendingCount == 0
        else -> false
    }
    val title = stringResource(when {
        status is SyncStatus.Running -> StringRes.parrot_cloud_syncing
        failed -> StringRes.parrot_cloud_cant_sync
        synced -> StringRes.parrot_cloud_all_synced
        state.profileLink == null -> StringRes.cloud_account_link_pending
        state.profileLink.syncEnabled.not() -> StringRes.cloud_account_sync_not_enabled
        else -> StringRes.parrot_cloud_ready_to_sync
    })
    val time = lastSyncLabel(state.lastSuccessfulSyncAt)
    EmberCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CloudText(title, bold = true, error = failed, success = synced)
                if (status is SyncStatus.Running) {
                    val totalItems = status.totalItems
                    if (totalItems != null) {
                        val progressLabel = when (status.phase) {
                            SyncPhase.UPLOADING_FILES, SyncPhase.DOWNLOADING_FILES, SyncPhase.TRANSFERRING_FILES -> StringRes.parrot_cloud_books_progress
                            else -> StringRes.parrot_cloud_changes_progress
                        }
                        CloudText(stringResource(progressLabel, status.completedItems, totalItems), secondary = true)
                    } else CloudText(stringResource(StringRes.parrot_cloud_changes_syncing), secondary = true)
                } else CloudText(time, secondary = true)
            }
            if (status !is SyncStatus.Running && state.profileLink != null) {
                CloudButton(
                    stringResource(if (failed) StringRes.parrot_cloud_try_again else StringRes.cloud_account_sync_now),
                    enabled = !state.isLoading && (status !is SyncStatus.Failed || status.canRetry),
                ) { dispatch(CloudAccountIntent.OnSyncClicked) }
            }
        }
        if (status is SyncStatus.Running) {
            val fraction = syncProgress(status)
            if (fraction != null) {
                Spacer(Modifier.height(12.dp))
                CloudProgress(fraction)
            }
        }
        if (failed) {
            Spacer(Modifier.height(12.dp))
            CloudNotice(if (status is SyncStatus.Offline) stringResource(StringRes.parrot_cloud_offline_sync) else syncErrorMessage((status as SyncStatus.Failed).error), isError = true)
        }
        Spacer(Modifier.height(12.dp))
        CloudDivider()
        Spacer(Modifier.height(10.dp))
        CloudText(stringResource(StringRes.parrot_cloud_syncs), secondary = true)
    }
}

@Composable
private fun ParrotCloudStorageCard(state: CloudAccountViewState) {
    val usage = state.storageUsage
    if (usage == null || usage.quotaBytes <= 0) {
        if (state.storageUsageError != null) CloudNotice(stringResource(StringRes.parrot_cloud_storage_unavailable), isError = true)
        return
    }
    val almostFull = isStorageAlmostFull(usage.usedBytes, usage.quotaBytes)
    val description = stringResource(StringRes.parrot_cloud_storage_accessibility, spokenStorageLabel(usage.usedBytes), spokenStorageLabel(usage.quotaBytes))
    EmberCard(contentPadding = 16.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CloudText(stringResource(StringRes.cloud_storage_usage_title), bold = true)
            CloudText(stringResource(StringRes.parrot_cloud_storage_used, storageLabel(usage.usedBytes), storageLabel(usage.quotaBytes)), secondary = true)
        }
        Spacer(Modifier.height(10.dp))
        CloudProgress((usage.usedBytes.toDouble() / usage.quotaBytes).toFloat(), error = almostFull, modifier = Modifier.semantics { contentDescription = description })
        if (almostFull) {
            Spacer(Modifier.height(8.dp))
            CloudText(stringResource(StringRes.parrot_cloud_storage_almost_full), error = true)
        }
    }
}

@Composable
private fun ParrotCloudSettingsCard(state: CloudAccountViewState, dispatch: IntentDispatcher<CloudAccountIntent>) {
    EmberCard(contentPadding = 0.dp) {
        state.profileLink?.let { link ->
            Row(
                Modifier.fillMaxWidth().toggleable(link.autoBackupEnabled, enabled = !state.isLoading && !state.isUpdatingAutoBackup, role = Role.Switch) {
                    dispatch(CloudAccountIntent.OnAutoBackupToggled(it))
                }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    CloudText(stringResource(StringRes.parrot_cloud_automatic_books), bold = true)
                    CloudText(stringResource(StringRes.parrot_cloud_automatic_books_helper), secondary = true)
                }
                CloudSwitch(link.autoBackupEnabled)
            }
            CloudDivider()
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(enabled = !state.isLoading && state.syncStatus !is SyncStatus.Running, role = Role.Button) { dispatch(CloudAccountIntent.OnSignOutClicked) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudText(stringResource(StringRes.cloud_account_sign_out), bold = true, modifier = Modifier.weight(1f))
            CloudText(stringResource(StringRes.parrot_cloud_books_stay), secondary = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CloudAccountConfirmations(state: CloudAccountViewState, dispatch: IntentDispatcher<CloudAccountIntent>) {
    if (state.showLinkConfirmation) {
        CloudConfirmation(
            stringResource(StringRes.cloud_account_link_profile_title),
            stringResource(StringRes.cloud_account_link_profile),
            onConfirm = { dispatch(CloudAccountIntent.OnLinkConfirmed) },
            onDismiss = { dispatch(CloudAccountIntent.OnLinkDismissed("dismiss_request")) },
        ) { CloudText(stringResource(StringRes.cloud_account_link_profile_description)) }
    }
    if (state.showAutoBackupConfirmation) {
        CloudConfirmation(
            stringResource(StringRes.parrot_cloud_automatic_books),
            stringResource(StringRes.cloud_backup_autobackup_enable),
            confirmEnabled = state.autoBackupRightsAttested && !state.isUpdatingAutoBackup,
            dismissEnabled = !state.isUpdatingAutoBackup,
            onConfirm = { dispatch(CloudAccountIntent.OnAutoBackupConfirmed) },
            onDismiss = { dispatch(CloudAccountIntent.OnAutoBackupDismissed("dismiss_request")) },
        ) {
            CloudText(stringResource(StringRes.parrot_cloud_upload_consent))
            ConsentRow(stringResource(StringRes.cloud_backup_attestation_checkbox), state.autoBackupRightsAttested, !state.isUpdatingAutoBackup) {
                dispatch(CloudAccountIntent.OnAutoBackupAttestationChanged(it))
            }
        }
    }
    if (state.showSignOutConfirmation) {
        SignOutConfirmationDialog(
            pendingCount = state.signOutPendingCount,
            isSigningOut = state.isSigningOut,
            onSyncFirst = { dispatch(CloudAccountIntent.OnSignOutSyncFirstClicked) },
            onConfirm = { dispatch(CloudAccountIntent.OnSignOutConfirmed) },
            onDismiss = { dispatch(CloudAccountIntent.OnSignOutDismissed) },
        )
    }
    if (state.showDeleteAccountConfirmation) {
        var confirmation by remember { mutableStateOf("") }
        CloudConfirmation(
            stringResource(StringRes.parrot_cloud_delete_title),
            stringResource(StringRes.cloud_account_delete_confirm),
            destructive = true,
            confirmEnabled = canConfirmDeletion(confirmation) && !state.isLoading,
            onConfirm = { dispatch(CloudAccountIntent.OnDeleteAccountConfirmed) },
            onDismiss = { dispatch(CloudAccountIntent.OnDeleteAccountDismissed("dismiss_request")) },
        ) {
            CloudText(stringResource(StringRes.parrot_cloud_delete_message))
            EmberSectionLabel(stringResource(StringRes.parrot_cloud_delete_field))
            val fieldLabel = stringResource(StringRes.parrot_cloud_delete_field)
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = cloudFieldColors(),
                textStyle = Ember.type.meta,
                modifier = Modifier.fillMaxWidth().cloudFieldOutline(false).semantics { contentDescription = fieldLabel },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        }
    }
}

/**
 * Custom dialog shell. E-ink uses a Popup (no dimmed scrim, focusable so Back and
 * tap-outside dismiss it); other modes use a regular [Dialog].
 */
@Composable
private fun CloudDialogShell(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (Ember.style.isEink) {
        // Popup has no scrim; focusable makes Back dismiss it and prevents background input.
        androidx.compose.ui.window.Popup(
            alignment = Alignment.Center,
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) { Box(Modifier.fillMaxWidth().padding(24.dp)) { content() } }
    } else {
        Dialog(onDismissRequest = onDismiss) { content() }
    }
}

@Composable
private fun CloudConfirmation(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CloudDialogShell(onDismiss = { if (dismissEnabled) onDismiss() }) {
        EmberCard(contentPadding = 20.dp) {
            Text(title, style = Ember.type.screenTitle.copy(fontSize = 20.sp), color = Ember.colors.ink)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                CloudLink(stringResource(StringRes.general_cancel), enabled = dismissEnabled, onClick = onDismiss)
                CloudButton(confirmLabel, enabled = confirmEnabled, destructive = destructive, onClick = onConfirm)
            }
        }
    }
}

/**
 * Sign-out confirmation — always shown. Without unsynced changes it is a simple
 * Cancel / Sign out row; with unsynced changes it adds an error box and stacks
 * Sync first / Sign out anyway / Cancel. Sign out is not destructive (not red).
 */
@Composable
private fun SignOutConfirmationDialog(
    pendingCount: Int,
    isSigningOut: Boolean,
    onSyncFirst: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    CloudDialogShell(onDismiss = { if (!isSigningOut) onDismiss() }) {
        EmberCard(contentPadding = 20.dp) {
            Text(
                text = stringResource(StringRes.parrot_cloud_sign_out_title),
                style = Ember.type.screenTitle.copy(fontSize = 20.sp),
                color = Ember.colors.ink,
            )
            Spacer(Modifier.height(16.dp))
            CloudText(stringResource(StringRes.parrot_cloud_sign_out_body))
            if (pendingCount > 0) {
                Spacer(Modifier.height(16.dp))
                SignOutPendingError(pendingCount)
            }
            Spacer(Modifier.height(16.dp))
            if (pendingCount > 0) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CloudTextAction(
                        stringResource(StringRes.parrot_cloud_sync_first),
                        Ember.colors.accentText,
                        enabled = !isSigningOut,
                        onClick = onSyncFirst,
                    )
                    CloudTextAction(
                        stringResource(StringRes.parrot_cloud_sign_out_anyway),
                        Ember.colors.ink,
                        enabled = !isSigningOut,
                        onClick = onConfirm,
                    )
                    CloudTextAction(
                        stringResource(StringRes.general_cancel),
                        Ember.colors.ink,
                        enabled = !isSigningOut,
                        onClick = onDismiss,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CloudTextAction(
                        stringResource(StringRes.general_cancel),
                        Ember.colors.ink,
                        enabled = !isSigningOut,
                        onClick = onDismiss,
                    )
                    Spacer(Modifier.width(12.dp))
                    CloudTextAction(
                        stringResource(StringRes.cloud_account_sign_out),
                        Ember.colors.accentText,
                        enabled = !isSigningOut,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
private fun SignOutPendingError(count: Int) {
    val lead = if (count == 1) {
        stringResource(StringRes.parrot_cloud_sign_out_pending_one)
    } else {
        stringResource(StringRes.parrot_cloud_sign_out_pending_many, count)
    }
    val tail = stringResource(StringRes.parrot_cloud_sign_out_pending_tail)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Ember.colors.errorContainer)
            .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, shape) else Modifier)
            .padding(12.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }
                append(" ")
                append(tail)
            },
            color = Ember.colors.destructive,
            style = Ember.type.meta.copy(fontSize = 15.sp),
        )
    }
}

@Composable
private fun CloudTextAction(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = Ember.colors.ink2),
    ) {
        Text(text, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun ConsentRow(text: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp).border(if (Ember.style.isEink) 2.dp else 1.5.dp, Ember.colors.ink2, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (checked) CloudText("✓")
        }
        CloudText(text, secondary = true)
    }
}

@Composable
private fun CloudSwitch(checked: Boolean) {
    val colors = Ember.colors
    Box(
        Modifier.size(52.dp, 32.dp).clip(CircleShape).background(if (checked) colors.accent else colors.track)
            .then(if (Ember.style.isEink) Modifier.border(2.dp, colors.line, CircleShape) else Modifier),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(4.dp).size(24.dp).clip(CircleShape).background(if (checked) colors.onAccent else colors.ink2))
    }
}

@Composable
private fun CloudProgress(progress: Float, error: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Ember.colors.track)
            .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, CircleShape) else Modifier),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(if (error) Ember.colors.destructive else Ember.colors.accent))
    }
}

@Composable
private fun CloudDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = if (Ember.style.isEink) 2.dp else 1.dp, color = Ember.colors.line)
}

@Composable
private fun CloudText(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, bold: Boolean = false, error: Boolean = false, success: Boolean = false) {
    Text(text, modifier, style = Ember.type.meta.copy(fontSize = if (secondary) 13.sp else if (bold) 16.sp else 15.sp, fontWeight = if (bold || error && Ember.style.isEink) FontWeight.Bold else FontWeight.Normal),
        color = when { error -> Ember.colors.destructive; success -> Ember.colors.success; secondary -> Ember.colors.ink2; else -> Ember.colors.ink })
}

@Composable
private fun CloudNotice(message: String, isError: Boolean = false, action: @Composable (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (isError) Ember.colors.errorContainer else Ember.colors.surface)
            .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, shape) else Modifier).padding(12.dp),
    ) {
        CloudText(message, error = isError)
        action?.invoke()
    }
}

@Composable
private fun CloudLink(text: String, modifier: Modifier = Modifier, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) Ember.colors.destructive else Ember.colors.accentText, disabledContentColor = Ember.colors.ink2)) {
        Text(text, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
    }
}

@Composable
private fun CloudButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = Ember.colors
    val outlined = !primary || Ember.style.isEink
    Button(
        onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = CircleShape,
        border = if (outlined) BorderStroke(if (Ember.style.isEink) 2.dp else 1.5.dp, if (destructive) colors.destructive else colors.chipBorder) else null,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (outlined) Color.Transparent else colors.accent,
            contentColor = if (destructive) colors.destructive else if (outlined) colors.ink else colors.onAccent,
            disabledContainerColor = if (outlined) Color.Transparent else colors.track,
            disabledContentColor = colors.ink2,
        ),
    ) { Text(text, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)) }
}

@Composable
private fun cloudFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ember.colors.ink, unfocusedTextColor = Ember.colors.ink,
    disabledTextColor = Ember.colors.ink2, errorTextColor = Ember.colors.ink,
    cursorColor = Ember.colors.accent,
    focusedContainerColor = Ember.colors.surface, unfocusedContainerColor = Ember.colors.surface,
    disabledContainerColor = Ember.colors.surface, errorContainerColor = Ember.colors.surface,
    focusedBorderColor = Ember.colors.accent, unfocusedBorderColor = Ember.colors.line,
    disabledBorderColor = Ember.colors.line, errorBorderColor = Ember.colors.destructive,
)

@Composable
private fun Modifier.cloudFieldOutline(isError: Boolean): Modifier = if (isError || Ember.style.isEink) {
    border(2.dp, if (isError) Ember.colors.destructive else Ember.colors.line, RoundedCornerShape(16.dp))
} else this

@Composable
private fun spokenStorageLabel(bytes: Long): String {
    val label = storageLabel(bytes)
    val unit = stringResource(when (label.substringAfterLast(' ')) {
        "GB" -> StringRes.parrot_cloud_gigabytes
        "MB" -> StringRes.parrot_cloud_megabytes
        else -> StringRes.parrot_cloud_kilobytes
    })
    return "${label.substringBefore(' ')} $unit"
}

@Composable
private fun accountErrorMessage(error: CloudAccountError): String = stringResource(when (error) {
    CloudAccountError.InvalidCredentials -> StringRes.parrot_cloud_credentials_error
    CloudAccountError.NetworkUnavailable -> StringRes.parrot_cloud_offline_sign_in
    CloudAccountError.WeakPassword -> StringRes.parrot_cloud_weak_password
    CloudAccountError.EmailAlreadyRegistered -> StringRes.parrot_cloud_email_registered
    CloudAccountError.ProfileAlreadyLinked -> StringRes.cloud_account_profile_already_linked
    CloudAccountError.NotConfigured -> StringRes.cloud_account_not_configured
    CloudAccountError.DeleteReauthenticationRequired -> StringRes.cloud_account_delete_reauthentication_required
    CloudAccountError.Generic -> StringRes.cloud_account_generic_error
})

@Composable
private fun syncErrorMessage(raw: String): String = stringResource(when (syncFailureKind(raw)) {
    SyncFailureKind.Network -> StringRes.parrot_cloud_offline_sync
    SyncFailureKind.Timeout -> StringRes.parrot_cloud_sync_timeout
    SyncFailureKind.Quota -> StringRes.parrot_cloud_storage_almost_full
    SyncFailureKind.Authentication -> StringRes.parrot_cloud_sync_authentication
    SyncFailureKind.Other -> StringRes.parrot_cloud_sync_error
})

@Composable
private fun lastSyncLabel(timestamp: String?): String {
    val elapsed = elapsedSyncMinutes(timestamp, Clock.System.now())
    return when {
        elapsed == null -> stringResource(StringRes.parrot_cloud_not_synced)
        elapsed < 1 -> stringResource(StringRes.parrot_cloud_synced_now)
        elapsed == 1L -> stringResource(StringRes.parrot_cloud_synced_minute)
        elapsed < 60 -> stringResource(StringRes.parrot_cloud_synced_minutes, elapsed)
        elapsed < 120 -> stringResource(StringRes.parrot_cloud_synced_hour)
        elapsed < 1440 -> stringResource(StringRes.parrot_cloud_synced_hours, elapsed / 60)
        isYesterday(timestamp!!, Clock.System.now()) -> stringResource(StringRes.parrot_cloud_synced_yesterday, localSyncTime(timestamp))
        else -> stringResource(StringRes.parrot_cloud_synced_date, localSyncDate(timestamp))
    }
}
