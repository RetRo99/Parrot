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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerType
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.action_delete
import resources.translations.app_settings_logout
import resources.translations.general_back
import resources.translations.general_retry
import resources.translations.settings_server_management_add
import resources.translations.settings_server_management_empty
import resources.translations.settings_server_management_empty_hint
import resources.translations.settings_server_management_title
import resources.translations.settings_server_login_action
import resources.translations.settings_server_operation_failed
import resources.translations.settings_server_operation_retry
import resources.translations.settings_server_logged_in_as
import resources.translations.settings_server_login_failed
import resources.translations.settings_server_not_logged_in
import resources.translations.settings_server_session_expired
import resources.translations.settings_server_type_label

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
    val snackbarHostState = remember { SnackbarHostState() }
    val failureMessage = stringResource(StringRes.settings_server_operation_failed)
    val retryLabel = stringResource(StringRes.settings_server_operation_retry)

    LaunchedEffect(viewState.operationFailure) {
        if (viewState.operationFailure != null) {
            val result = snackbarHostState.showSnackbar(
                message = failureMessage,
                actionLabel = retryLabel,
                withDismissAction = true,
            )
            intentDispatcher(
                if (result == SnackbarResult.ActionPerformed) {
                    ServerManagementIntent.RetryFailedOperation
                } else {
                    ServerManagementIntent.DismissOperationFailure
                },
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            ExtendedFloatingActionButton(
                onClick = { intentDispatcher(ServerManagementIntent.OnAddServerClick) },
                icon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(StringRes.settings_server_management_add),
                    )
                },
                text = { Text(stringResource(StringRes.settings_server_management_add)) },
            )
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
                            onLoginClick = {
                                intentDispatcher(
                                    ServerManagementIntent.OnLoginClick(
                                        serverId = serverWithStatus.server.id,
                                        serverType = serverWithStatus.server.type,
                                    ),
                                )
                            },
                            onLogoutClick = {
                                intentDispatcher(
                                    ServerManagementIntent.OnLogoutClick(
                                        serverId = serverWithStatus.server.id,
                                        serverType = serverWithStatus.server.type,
                                    ),
                                )
                            },
                            onRemoveClick = {
                                intentDispatcher(
                                    ServerManagementIntent.OnRemoveClick(
                                        serverId = serverWithStatus.server.id,
                                        serverType = serverWithStatus.server.type,
                                    ),
                                )
                            },
                            actionsEnabled = !viewState.isOperationInProgress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerListItem(
    serverWithStatus: ServerWithStatusUiModel,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onRemoveClick: () -> Unit,
    actionsEnabled: Boolean,
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
                    text = stringResource(StringRes.settings_server_type_label, server.type.displayName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            }

            if (authState is ServerAuthState.Authenticated) {
                IconButton(onClick = onLogoutClick, enabled = actionsEnabled) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = stringResource(StringRes.app_settings_logout),
                    )
                }
            } else if (
                server.type == ServerType.Storyteller ||
                server.type == ServerType.Audiobookshelf
            ) {
                TextButton(onClick = onLoginClick, enabled = actionsEnabled) {
                    Text(
                        text = stringResource(
                            if (authState is ServerAuthState.NotAuthenticated) {
                                StringRes.settings_server_login_action
                            } else {
                                StringRes.general_retry
                            },
                        ),
                    )
                }
            }

            IconButton(onClick = onRemoveClick, enabled = actionsEnabled) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(StringRes.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ServerAuthState.toDisplayString(): String = when (this) {
    is ServerAuthState.Authenticated ->
        stringResource(StringRes.settings_server_logged_in_as, username)
    is ServerAuthState.NotAuthenticated ->
        stringResource(StringRes.settings_server_not_logged_in)
    is ServerAuthState.TokenExpired ->
        stringResource(StringRes.settings_server_session_expired)
    is ServerAuthState.AuthenticationFailed ->
        stringResource(StringRes.settings_server_login_failed)
}

@Composable
private fun ServerAuthState.toColor() = when (this) {
    is ServerAuthState.Authenticated -> MaterialTheme.colorScheme.primary
    is ServerAuthState.NotAuthenticated -> MaterialTheme.colorScheme.onSurfaceVariant
    is ServerAuthState.TokenExpired -> MaterialTheme.colorScheme.error
    is ServerAuthState.AuthenticationFailed -> MaterialTheme.colorScheme.error
}
