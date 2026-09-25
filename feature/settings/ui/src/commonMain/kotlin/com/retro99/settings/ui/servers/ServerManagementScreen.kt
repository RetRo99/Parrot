package com.retro99.settings.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.library.domain.grouping.AudiobookshelfPairingActionStatus
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.login_sign_in_button
import resources.translations.settings_server_management_add
import resources.translations.settings_server_management_empty
import resources.translations.settings_server_management_empty_hint
import resources.translations.settings_server_management_title
import resources.translations.settings_server_pairing_action
import resources.translations.settings_server_pairing_account_id_unavailable
import resources.translations.settings_server_pairing_account_required
import resources.translations.settings_server_pairing_cloud_account
import resources.translations.settings_server_pairing_cloud_mismatch
import resources.translations.settings_server_pairing_code
import resources.translations.settings_server_pairing_code_created
import resources.translations.settings_server_pairing_code_hint
import resources.translations.settings_server_pairing_code_invalid
import resources.translations.settings_server_pairing_code_ready
import resources.translations.settings_server_pairing_conflicts
import resources.translations.settings_server_pairing_create_code
import resources.translations.settings_server_pairing_dismiss
import resources.translations.settings_server_pairing_import_code
import resources.translations.settings_server_pairing_import_complete
import resources.translations.settings_server_pairing_import_hint
import resources.translations.settings_server_pairing_manage
import resources.translations.settings_server_pairing_not_authenticated
import resources.translations.settings_server_pairing_reauth_required
import resources.translations.settings_server_pairing_same_server_required
import resources.translations.settings_server_pairing_server_unavailable
import resources.translations.settings_server_pairing_source_mismatch
import resources.translations.settings_server_pairing_status_checking
import resources.translations.settings_server_pairing_status_paired
import resources.translations.settings_server_pairing_status_unpaired
import resources.translations.settings_server_pairing_title
import resources.translations.settings_server_pairing_unpair
import resources.translations.settings_server_pairing_unpair_blocked
import resources.translations.settings_server_pairing_unpair_complete
import resources.translations.settings_server_pairing_unexpected_error
import resources.translations.settings_server_pairing_unsupported_code
import resources.translations.settings_server_reauthenticate

@Composable
fun ServerManagementScreen(
    onNavigateToLogin: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerManagementViewModel = koinViewModel { parametersOf(onNavigateToLogin) },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        ServerManagementScreenContent(
            viewState = viewState,
            intentDispatcher = intentDispatcher,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerManagementScreenContent(
    viewState: ServerManagementViewState,
    intentDispatcher: IntentDispatcher<ServerManagementIntent>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(StringRes.settings_server_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(StringRes.general_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { intentDispatcher(ServerManagementIntent.OnAddServerClick) },
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(StringRes.settings_server_management_add),
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            if (viewState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (viewState.servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(StringRes.settings_server_management_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(StringRes.settings_server_management_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(viewState.servers, key = { it.server.id }) { serverWithStatus ->
                        ServerListItem(
                            serverWithStatus = serverWithStatus,
                            onLogoutClick = {
                                intentDispatcher(
                                    ServerManagementIntent.OnLogoutClick(
                                        serverWithStatus.server.id,
                                    ),
                                )
                            },
                            onRemoveClick = {
                                intentDispatcher(
                                    ServerManagementIntent.OnRemoveClick(
                                        serverWithStatus.server.id,
                                    ),
                                )
                            },
                            onLoginClick = {
                                intentDispatcher(
                                    ServerManagementIntent.OnLoginClick(
                                        serverWithStatus.server.id,
                                    ),
                                )
                            },
                            pairingStatus = viewState.pairingStatuses[serverWithStatus.server.id],
                            isPairingActionInProgress = viewState.isPairingActionInProgress,
                            onPairingClick = {
                                intentDispatcher(
                                    ServerManagementIntent.OnPairingClick(
                                        serverWithStatus.server.id,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            PairingDialog(viewState = viewState, intentDispatcher = intentDispatcher)
        }
    }
}

@Composable
private fun ServerListItem(
    serverWithStatus: ServerWithStatusUiModel,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onRemoveClick: () -> Unit,
    pairingStatus: LibrarySourceIdentityPairingStatus?,
    isPairingActionInProgress: Boolean,
    onPairingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val server = serverWithStatus.server
    val authState = serverWithStatus.authState

    Card(
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = server.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = authState.toDisplayString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = authState.toColor(),
                )
                if (server.type == ServerType.Audiobookshelf) {
                    pairingStatus?.let { status ->
                        Text(
                            text = stringResource(status.stringRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = status.toColor(),
                        )
                    }
                    TextButton(
                        enabled = !isPairingActionInProgress,
                        onClick = onPairingClick,
                    ) {
                        Text(
                            text = stringResource(
                                if (pairingStatus == LibrarySourceIdentityPairingStatus.Paired) {
                                    StringRes.settings_server_pairing_manage
                                } else {
                                    StringRes.settings_server_pairing_action
                                },
                            ),
                        )
                    }
                }
            }

            if (server.type == ServerType.Storyteller) {
                TextButton(onClick = onLoginClick) {
                    Text(
                        stringResource(
                            if (authState is ServerAuthState.Authenticated) {
                                StringRes.settings_server_reauthenticate
                            } else {
                                StringRes.login_sign_in_button
                            },
                        ),
                    )
                }
            }

            if (authState is ServerAuthState.Authenticated) {
                IconButton(onClick = onLogoutClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                    )
                }
            }

            IconButton(onClick = onRemoveClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PairingDialog(
    viewState: ServerManagementViewState,
    intentDispatcher: IntentDispatcher<ServerManagementIntent>,
) {
    val server = viewState.servers.firstOrNull { item ->
        item.server.id == viewState.pairingServerId
    } ?: return
    val status = viewState.pairingStatuses[server.server.id]
    val isPaired = status == LibrarySourceIdentityPairingStatus.Paired
    val canManagePairing = viewState.cloudAuthState is CloudAuthState.SignedIn &&
        server.authState is ServerAuthState.Authenticated &&
        (status == LibrarySourceIdentityPairingStatus.Unpaired || isPaired)

    AlertDialog(
        onDismissRequest = {
            if (!viewState.isPairingActionInProgress) {
                intentDispatcher(ServerManagementIntent.OnPairingDismissed)
            }
        },
        title = {
            Text(stringResource(StringRes.settings_server_pairing_title, server.server.name))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(StringRes.settings_server_pairing_same_server_required))
                Text(
                    text = stringResource(
                        status?.stringRes ?: StringRes.settings_server_pairing_status_checking,
                    ),
                    color = status?.toColor() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PairingContextMessage(authState = viewState.cloudAuthState)
                if (viewState.pairingActionStatus != null) {
                    PairingActionMessage(
                        status = viewState.pairingActionStatus,
                        action = viewState.pairingAction,
                        conflictCount = viewState.pairingConflictCount,
                    )
                }
                if (viewState.pairingUnexpectedError) {
                    Text(stringResource(StringRes.settings_server_pairing_unexpected_error))
                }
                viewState.displayedPairingCode?.let { code ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(StringRes.settings_server_pairing_code_created))
                        SelectionContainer {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(StringRes.settings_server_pairing_code_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!isPaired) {
                    OutlinedTextField(
                        value = viewState.pairingCodeInput,
                        onValueChange = { code ->
                            intentDispatcher(ServerManagementIntent.OnPairingCodeChanged(code))
                        },
                        label = { Text(stringResource(StringRes.settings_server_pairing_code)) },
                        supportingText = {
                            Text(stringResource(StringRes.settings_server_pairing_import_hint))
                        },
                        singleLine = true,
                        enabled = !viewState.isPairingActionInProgress,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPaired) {
                    TextButton(
                        enabled = canManagePairing && !viewState.isPairingActionInProgress,
                        onClick = {
                            intentDispatcher(ServerManagementIntent.OnShowPairingCodeClick)
                        },
                    ) {
                        Text(stringResource(StringRes.settings_server_pairing_code))
                    }
                    Button(
                        enabled = canManagePairing && !viewState.isPairingActionInProgress,
                        onClick = {
                            intentDispatcher(ServerManagementIntent.OnUnpairClick)
                        },
                    ) {
                        Text(stringResource(StringRes.settings_server_pairing_unpair))
                    }
                } else {
                    TextButton(
                        enabled = canManagePairing && !viewState.isPairingActionInProgress,
                        onClick = {
                            intentDispatcher(ServerManagementIntent.OnCreatePairingCodeClick)
                        },
                    ) {
                        Text(stringResource(StringRes.settings_server_pairing_create_code))
                    }
                    Button(
                        enabled = canManagePairing &&
                            viewState.pairingCodeInput.isNotBlank() &&
                            !viewState.isPairingActionInProgress,
                        onClick = {
                            intentDispatcher(ServerManagementIntent.OnImportPairingCodeClick)
                        },
                    ) {
                        Text(stringResource(StringRes.settings_server_pairing_import_code))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !viewState.isPairingActionInProgress,
                onClick = { intentDispatcher(ServerManagementIntent.OnPairingDismissed) },
            ) {
                Text(stringResource(StringRes.settings_server_pairing_dismiss))
            }
        },
    )
}

@Composable
private fun PairingContextMessage(authState: CloudAuthState) {
    when (authState) {
        CloudAuthState.RestoringSession -> Text(
            stringResource(StringRes.settings_server_pairing_status_checking),
        )
        is CloudAuthState.ReauthenticationRequired,
        is CloudAuthState.RefreshUnavailable,
        -> Text(stringResource(StringRes.settings_server_pairing_reauth_required))
        CloudAuthState.SignedOut,
        is CloudAuthState.AwaitingEmailVerification,
        -> Text(stringResource(StringRes.settings_server_pairing_account_required))
        is CloudAuthState.SignedIn -> Text(
            stringResource(
                StringRes.settings_server_pairing_cloud_account,
                authState.account.email ?: authState.account.id,
            ),
        )
    }
}

@Composable
private fun PairingActionMessage(
    status: AudiobookshelfPairingActionStatus,
    action: PairingAction?,
    conflictCount: Int,
) {
    val message = when (status) {
        AudiobookshelfPairingActionStatus.Completed -> if (conflictCount > 0) {
            stringResource(StringRes.settings_server_pairing_conflicts, conflictCount)
        } else {
            when (action) {
                PairingAction.CreateCode,
                PairingAction.ShowCode,
                -> stringResource(StringRes.settings_server_pairing_code_ready)
                PairingAction.ImportCode -> {
                    stringResource(StringRes.settings_server_pairing_import_complete)
                }
                PairingAction.Unpair -> {
                    stringResource(StringRes.settings_server_pairing_unpair_complete)
                }
                null -> stringResource(StringRes.settings_server_pairing_import_complete)
            }
        }
        AudiobookshelfPairingActionStatus.CloudAccountRequired -> {
            stringResource(StringRes.settings_server_pairing_account_required)
        }
        AudiobookshelfPairingActionStatus.CloudAccountMismatch -> {
            stringResource(StringRes.settings_server_pairing_cloud_mismatch)
        }
        AudiobookshelfPairingActionStatus.SourceAccountMismatch -> {
            stringResource(StringRes.settings_server_pairing_source_mismatch)
        }
        AudiobookshelfPairingActionStatus.SourceAccountIdUnavailable -> {
            stringResource(StringRes.settings_server_pairing_account_id_unavailable)
        }
        AudiobookshelfPairingActionStatus.NotAuthenticated -> {
            stringResource(StringRes.settings_server_pairing_not_authenticated)
        }
        AudiobookshelfPairingActionStatus.AlreadyPaired -> {
            stringResource(StringRes.settings_server_pairing_status_paired)
        }
        AudiobookshelfPairingActionStatus.InvalidCode -> {
            stringResource(StringRes.settings_server_pairing_code_invalid)
        }
        AudiobookshelfPairingActionStatus.UnsupportedCodeVersion -> {
            stringResource(StringRes.settings_server_pairing_unsupported_code)
        }
        AudiobookshelfPairingActionStatus.ServerUnavailable -> {
            stringResource(StringRes.settings_server_pairing_server_unavailable)
        }
        AudiobookshelfPairingActionStatus.HasSharedMemberships -> {
            stringResource(StringRes.settings_server_pairing_unpair_blocked, conflictCount)
        }
    }
    Text(message, color = MaterialTheme.colorScheme.error)
}

private val LibrarySourceIdentityPairingStatus.stringRes
    get() = when (this) {
        LibrarySourceIdentityPairingStatus.Unpaired -> {
            StringRes.settings_server_pairing_status_unpaired
        }
        LibrarySourceIdentityPairingStatus.Paired -> StringRes.settings_server_pairing_status_paired
        LibrarySourceIdentityPairingStatus.CloudAccountRequired -> {
            StringRes.settings_server_pairing_account_required
        }
        LibrarySourceIdentityPairingStatus.CloudAccountMismatch -> {
            StringRes.settings_server_pairing_cloud_mismatch
        }
        LibrarySourceIdentityPairingStatus.SourceAccountMismatch -> {
            StringRes.settings_server_pairing_source_mismatch
        }
        LibrarySourceIdentityPairingStatus.NotAuthenticated -> {
            StringRes.settings_server_pairing_not_authenticated
        }
        LibrarySourceIdentityPairingStatus.ServerUnavailable -> {
            StringRes.settings_server_pairing_server_unavailable
        }
    }

@Composable
private fun LibrarySourceIdentityPairingStatus.toColor() = when (this) {
    LibrarySourceIdentityPairingStatus.Unpaired -> MaterialTheme.colorScheme.onSurfaceVariant
    LibrarySourceIdentityPairingStatus.Paired -> MaterialTheme.colorScheme.primary
    LibrarySourceIdentityPairingStatus.CloudAccountRequired,
    LibrarySourceIdentityPairingStatus.CloudAccountMismatch,
    LibrarySourceIdentityPairingStatus.SourceAccountMismatch,
    LibrarySourceIdentityPairingStatus.NotAuthenticated,
    LibrarySourceIdentityPairingStatus.ServerUnavailable,
    -> MaterialTheme.colorScheme.error
}

@Composable
private fun ServerAuthState.toDisplayString(): String = when (this) {
    is ServerAuthState.Authenticated -> "Logged in as $username"
    is ServerAuthState.NotAuthenticated -> "Not logged in"
    is ServerAuthState.TokenExpired -> "Session expired"
    is ServerAuthState.AuthenticationFailed -> "Login failed"
}

@Composable
private fun ServerAuthState.toColor() = when (this) {
    is ServerAuthState.Authenticated -> MaterialTheme.colorScheme.primary
    is ServerAuthState.NotAuthenticated -> MaterialTheme.colorScheme.onSurfaceVariant
    is ServerAuthState.TokenExpired -> MaterialTheme.colorScheme.error
    is ServerAuthState.AuthenticationFailed -> MaterialTheme.colorScheme.error
}
