package com.retro99.settings.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberChevron
import com.retro99.base.ui.compose.EmberCard
import com.retro99.catalogue.ui.settings.CatalogueLibraryCard
import com.retro99.catalogue.ui.settings.CatalogueLibraryAction
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerType
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.servers_add
import resources.translations.servers_card_description
import resources.translations.servers_empty_body
import resources.translations.servers_empty_title
import resources.translations.servers_intro
import resources.translations.servers_not_encrypted
import resources.translations.servers_open_details
import resources.translations.servers_open_sync_backup
import resources.translations.servers_sign_in_again
import resources.translations.servers_status_cant_sign_in
import resources.translations.servers_status_connected
import resources.translations.servers_status_connected_plain
import resources.translations.servers_status_password_rejected
import resources.translations.servers_status_session_expired
import resources.translations.servers_status_signed_out
import resources.translations.servers_status_signed_out_body
import resources.translations.servers_title
import resources.translations.settings_server_list_load_failed
import resources.translations.settings_server_operation_failed
import resources.translations.settings_server_operation_retry
import resources.translations.catalogue_sign_out_everything
import resources.translations.catalogue_libraries_title

@Composable
fun ServerManagementScreen(
    onNavigateToLogin: (String?, Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenSyncAndBackup: () -> Unit,
    stopPlaybackForServer: (String, DiagnosticContext) -> Unit,
    failedLoginServerIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    onBrowseCatalogue: (String) -> Unit = {},
    onCatalogueSettings: (String, Boolean) -> Unit = { _, _ -> },
    viewModel: ServerManagementViewModel = koinViewModel {
        parametersOf(onNavigateToLogin, stopPlaybackForServer)
    },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        ServerManagementScreenContent(
            viewState = viewState,
            intentDispatcher = intentDispatcher,
            failedLoginServerIds = failedLoginServerIds,
            onBack = onBack,
            onOpenSyncAndBackup = onOpenSyncAndBackup,
            modifier = modifier,
            onBrowseCatalogue = onBrowseCatalogue,
            onCatalogueSettings = onCatalogueSettings,
        )
    }
}

@Composable
fun ServerManagementScreenContent(
    viewState: ServerManagementViewState,
    intentDispatcher: IntentDispatcher<ServerManagementIntent>,
    failedLoginServerIds: Set<String>,
    onBack: () -> Unit,
    onOpenSyncAndBackup: () -> Unit,
    modifier: Modifier = Modifier,
    onBrowseCatalogue: (String) -> Unit = {},
    onCatalogueSettings: (String, Boolean) -> Unit = { _, _ -> },
) {
    val colors = Ember.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val failureMessage = stringResource(StringRes.settings_server_operation_failed)
    val retryLabel = stringResource(StringRes.settings_server_operation_retry)
    var detailServerId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailServer = viewState.servers.firstOrNull { item -> item.server.id == detailServerId }
    var signOutEverything by remember { mutableStateOf(false) }

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

    // The open server was removed (or never existed after a restore): fall back to the list.
    LaunchedEffect(detailServer, detailServerId, viewState.isLoading) {
        if (detailServerId != null && detailServer == null && !viewState.isLoading) {
            detailServerId = null
        }
    }

    if (detailServer != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            onBackCompleted = { detailServerId = null },
        )
    }

    Box(modifier = modifier.fillMaxSize().background(colors.bg).statusBarsPadding()) {
        if (detailServer != null) {
            ServerDetailScreen(
                serverWithStatus = detailServer,
                loginFailed = detailServer.server.id in failedLoginServerIds,
                actionsEnabled = !viewState.isOperationInProgress,
                intentDispatcher = intentDispatcher,
                onOpenSyncAndBackup = onOpenSyncAndBackup,
                onBack = { detailServerId = null },
            )
        } else {
            ServerListContent(
                viewState = viewState,
                failedLoginServerIds = failedLoginServerIds,
                intentDispatcher = intentDispatcher,
                onOpenDetails = { serverId -> detailServerId = serverId },
                onOpenSyncAndBackup = onOpenSyncAndBackup,
                onBack = onBack,
                onBrowseCatalogue = onBrowseCatalogue,
                onCatalogueSettings = onCatalogueSettings,
                onSignOutEverything = { signOutEverything = true },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (signOutEverything) CatalogueSignOutEverythingDialog(
        hasCatalogue = viewState.hasCatalogueSignOutDetail,
        onConfirm = { signOutEverything = false; intentDispatcher(ServerManagementIntent.OnSignOutEverything) },
        onDismiss = { signOutEverything = false },
    )
}

@Composable
internal fun ServerScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(StringRes.general_back),
                tint = Ember.colors.ink,
            )
        }
        Text(
            text = title,
            style = Ember.type.screenTitle.copy(fontSize = 26.sp, lineHeight = 32.sp),
            color = Ember.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ServerListContent(
    viewState: ServerManagementViewState,
    failedLoginServerIds: Set<String>,
    intentDispatcher: IntentDispatcher<ServerManagementIntent>,
    onOpenDetails: (String) -> Unit,
    onOpenSyncAndBackup: () -> Unit,
    onBack: () -> Unit,
    onBrowseCatalogue: (String) -> Unit,
    onCatalogueSettings: (String, Boolean) -> Unit,
    onSignOutEverything: () -> Unit,
) {
    val colors = Ember.colors
    Column(modifier = Modifier.fillMaxSize()) {
        ServerScreenHeader(
            title = stringResource(StringRes.catalogue_libraries_title),
            onBack = onBack,
        )
        when {
            viewState.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.accent)
            }

            viewState.serverListLoadFailed -> Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(StringRes.settings_server_list_load_failed),
                    style = Ember.type.meta.copy(fontSize = 15.sp),
                    color = colors.error,
                )
                TextButton(
                    onClick = { intentDispatcher(ServerManagementIntent.RetryServerListLoad) },
                ) {
                    Text(
                        text = stringResource(StringRes.settings_server_operation_retry),
                        color = colors.accentText,
                    )
                }
            }

            viewState.servers.isEmpty() && viewState.catalogueSources.isEmpty() -> EmptyServers(
                onAdd = { intentDispatcher(ServerManagementIntent.OnAddServerClick) },
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (viewState.catalogueSources.isEmpty()) item(key = "intro") {
                    Text(
                        text = stringResource(StringRes.servers_intro),
                        style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = colors.ink2,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(viewState.servers, key = { item -> item.server.id }) { serverWithStatus ->
                    val server = serverWithStatus.server
                    val status = serverWithStatus.toStatus(
                        loginFailed = server.id in failedLoginServerIds,
                    )
                    ServerCard(
                        serverWithStatus = serverWithStatus,
                        status = status,
                        actionsEnabled = !viewState.isOperationInProgress,
                        onOpenDetails = { onOpenDetails(server.id) },
                        onOpenSyncAndBackup = onOpenSyncAndBackup,
                        onSignInAgain = {
                            intentDispatcher(
                                ServerManagementIntent.OnLoginClick(
                                    serverId = server.id,
                                    serverType = server.type,
                                    isRetry = status.isProblem,
                                ),
                            )
                        },
                    )
                }
                val allCatalogues = viewState.catalogueSources.map { item ->
                    ServerConfig(item.server.id, item.server.name, item.server.type, item.server.baseUrl, 0, enabled = item.status.access != com.retro99.server.api.ServerAccessState.TurnedOff)
                }
                items(viewState.catalogueSources, key = { "catalogue-${it.server.id}" }) { catalogue ->
                    val config = allCatalogues.first { it.id == catalogue.server.id }
                    CatalogueLibraryCard(
                        config = config,
                        allSources = allCatalogues,
                        status = catalogue.status,
                        enabled = !viewState.isOperationInProgress,
                        onDetails = { onCatalogueSettings(config.id, false) },
                        onAction = { action -> when (action) {
                            CatalogueLibraryAction.Browse -> onBrowseCatalogue(config.id)
                            CatalogueLibraryAction.Account -> onCatalogueSettings(config.id, true)
                            CatalogueLibraryAction.Details -> onCatalogueSettings(config.id, false)
                            CatalogueLibraryAction.TurnOn -> intentDispatcher(ServerManagementIntent.OnTurnOnCatalogue(config.id))
                            CatalogueLibraryAction.Retry -> intentDispatcher(ServerManagementIntent.OnRetryCatalogue(config.id))
                        } },
                    )
                }
                if (viewState.catalogueOperationFailed) item(key = "catalogue-error") {
                    Text(stringResource(StringRes.settings_server_operation_failed), style = Ember.type.meta, color = colors.error)
                }
                item(key = "add") {
                    AddServerButton(
                        onClick = { intentDispatcher(ServerManagementIntent.OnAddServerClick) },
                    )
                }
                item(key = "sign-out-everything") {
                    TextButton(onClick = onSignOutEverything, enabled = !viewState.isOperationInProgress, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(StringRes.catalogue_sign_out_everything), style = Ember.type.label, color = colors.accentText)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    val colors = Ember.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.navActive)
                .then(
                    if (Ember.style.isEink) {
                        Modifier.border(2.dp, colors.line, RoundedCornerShape(28.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Dns,
                contentDescription = null,
                tint = colors.navActiveContent,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(StringRes.servers_empty_title),
            style = Ember.type.screenTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(StringRes.servers_empty_body),
            style = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        EmberFilledButton(
            text = stringResource(StringRes.servers_add),
            onClick = onAdd,
            leadingIcon = Icons.Outlined.Add,
        )
    }
}

@Composable
private fun AddServerButton(onClick: () -> Unit) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val shape = RoundedCornerShape(18.dp)
    val strokeWidth = if (isEink) 2.dp else 1.5.dp
    val lineColor = if (isEink) colors.line else colors.accent.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = lineColor,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(
                        width = strokeWidth.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                )
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = colors.accentText,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(StringRes.servers_add),
            style = Ember.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = colors.accentText,
        )
    }
}

@Composable
private fun ServerCard(
    serverWithStatus: ServerWithStatusUiModel,
    status: ServerStatus,
    actionsEnabled: Boolean,
    onOpenDetails: () -> Unit,
    onOpenSyncAndBackup: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    val colors = Ember.colors
    val server = serverWithStatus.server
    val statusText = statusSummary(status)
    val description = stringResource(
        StringRes.servers_card_description,
        server.name,
        serverHost(server.baseUrl),
        statusText,
    )
    val showSignIn = server.type.canSignInFromServers() &&
        (status == ServerStatus.SignedOut || status.isProblem)

    EmberCard(
        modifier = Modifier.semantics(mergeDescendants = false) { contentDescription = description },
        contentPadding = 0.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onOpenDetails),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerTile(name = server.name)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = Ember.type.cardTitle.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HostLine(baseUrl = server.baseUrl)
                }
                EmberChevron()
            }
            Spacer(Modifier.height(12.dp))
            StatusBlock(status = status)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSignIn) {
                    EmberFilledButton(
                        text = stringResource(StringRes.servers_sign_in_again),
                        onClick = onSignInAgain,
                        enabled = actionsEnabled,
                        minHeight = 44.dp,
                    )
                }
                if (server.type == ServerType.ParrotCloud && status !is ServerStatus.Connected) {
                    EmberFilledButton(
                        text = stringResource(StringRes.servers_open_sync_backup),
                        onClick = onOpenSyncAndBackup,
                        enabled = actionsEnabled,
                        minHeight = 44.dp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = server.type.displayName,
                    style = Ember.type.meta.copy(fontSize = if (Ember.style.isEink) 13.sp else 12.sp),
                    color = colors.ink2,
                )
            }
        }
    }
}

@Composable
internal fun ServerTile(name: String, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(14.dp)
    val isEink = Ember.style.isEink
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(if (isEink) Color.Black else colors.navActive),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            style = Ember.type.cardTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            color = if (isEink) Color.White else colors.accentText,
        )
    }
}

@Composable
internal fun HostLine(baseUrl: String) {
    val colors = Ember.colors
    val encrypted = isEncryptedAddress(baseUrl)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (encrypted) Icons.Outlined.Lock else Icons.Outlined.NoEncryption,
            contentDescription = null,
            tint = colors.ink2,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (encrypted) {
                serverHost(baseUrl)
            } else {
                "${serverHost(baseUrl)} · ${stringResource(StringRes.servers_not_encrypted)}"
            },
            style = Ember.type.meta.copy(fontSize = 14.sp),
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusBlock(status: ServerStatus) {
    val colors = Ember.colors
    when (status) {
        is ServerStatus.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot()
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusSummary(status),
                style = Ember.type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = colors.success,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        else -> {
            val isProblem = status.isProblem
            ServerMessageBox(
                headline = when (status) {
                    ServerStatus.SignedOut -> stringResource(StringRes.servers_status_signed_out)
                    else -> stringResource(StringRes.servers_status_cant_sign_in)
                },
                body = when (status) {
                    ServerStatus.PasswordRejected ->
                        stringResource(StringRes.servers_status_password_rejected)
                    ServerStatus.SessionExpired ->
                        stringResource(StringRes.servers_status_session_expired)
                    else -> stringResource(StringRes.servers_status_signed_out_body)
                },
                isError = isProblem,
            )
        }
    }
}

@Composable
internal fun StatusDot() {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(Ember.colors.success, CircleShape),
    )
}

@Composable
internal fun ServerMessageBox(headline: String, body: String, isError: Boolean) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(14.dp)
    val ink = if (isError) colors.error else colors.ink
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isError) colors.errorContainer else colors.bg)
            .then(
                if (Ember.style.isEink) {
                    Modifier.border(2.dp, colors.line, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = headline,
            style = Ember.type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = ink,
        )
    }
}

@Composable
internal fun statusSummary(status: ServerStatus): String = when (status) {
    is ServerStatus.Connected -> stringResource(StringRes.servers_status_connected, status.account)
    ServerStatus.SignedOut -> stringResource(StringRes.servers_status_signed_out)
    ServerStatus.PasswordRejected, ServerStatus.SessionExpired ->
        stringResource(StringRes.servers_status_cant_sign_in)
}

/** Filled primary button: accent fill (black on e-ink), at least 44dp tall. */
@Composable
internal fun EmberFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 52.dp,
    leadingIcon: ImageVector? = null,
) {
    val colors = Ember.colors
    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = Ember.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = colors.onAccent,
        )
    }
}
