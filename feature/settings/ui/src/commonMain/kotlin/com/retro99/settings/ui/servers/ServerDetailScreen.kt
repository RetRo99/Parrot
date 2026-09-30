package com.retro99.settings.ui.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberChevron
import com.retro99.base.ui.compose.EmberGroupCard
import com.retro99.base.ui.compose.EmberRowDivider
import com.retro99.base.ui.compose.EmberSectionHeader
import com.retro99.base.ui.compose.EmberSettingRow
import com.retro99.server.api.ServerType
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.server_detail_address
import resources.translations.server_detail_address_error
import resources.translations.server_detail_edit_address_title
import resources.translations.server_detail_edit_name_title
import resources.translations.server_detail_name
import resources.translations.server_detail_not_signed_in
import resources.translations.server_detail_remove
import resources.translations.server_detail_remove_body
import resources.translations.server_detail_remove_confirm
import resources.translations.server_detail_remove_hint
import resources.translations.server_detail_remove_title
import resources.translations.server_detail_save
import resources.translations.server_detail_section_account
import resources.translations.server_detail_section_connection
import resources.translations.server_detail_section_remove
import resources.translations.server_detail_sign_in
import resources.translations.server_detail_sign_out
import resources.translations.server_detail_sign_out_body
import resources.translations.server_detail_sign_out_hint
import resources.translations.server_detail_sign_out_title
import resources.translations.server_detail_signed_in_as
import resources.translations.server_detail_type_server
import resources.translations.servers_status_connected_plain
import resources.translations.servers_open_sync_backup
import resources.translations.servers_status_signed_out
import resources.translations.servers_status_cant_sign_in

private enum class DetailDialog { Rename, Address, SignOut, Remove }

@Composable
internal fun ServerDetailScreen(
    serverWithStatus: ServerWithStatusUiModel,
    loginFailed: Boolean,
    actionsEnabled: Boolean,
    intentDispatcher: IntentDispatcher<ServerManagementIntent>,
    onBack: () -> Unit,
    onOpenSyncAndBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val server = serverWithStatus.server
    val status = serverWithStatus.toStatus(loginFailed)
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }
    val isConnected = status is ServerStatus.Connected
    val host = serverHost(server.baseUrl)

    Column(modifier = modifier.fillMaxSize()) {
        ServerScreenHeader(title = server.name, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerTile(name = server.name)
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isConnected) {
                            StatusDot()
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = when (status) {
                                is ServerStatus.Connected ->
                                    stringResource(StringRes.servers_status_connected_plain)
                                ServerStatus.SignedOut ->
                                    stringResource(StringRes.servers_status_signed_out)
                                else -> stringResource(StringRes.servers_status_cant_sign_in)
                            },
                            style = Ember.type.label.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = when {
                                isConnected -> colors.success
                                status.isProblem -> colors.error
                                else -> colors.ink2
                            },
                        )
                    }
                    Text(
                        text = stringResource(StringRes.server_detail_type_server, server.type.displayName),
                        style = Ember.type.meta.copy(fontSize = 14.sp),
                        color = colors.ink2,
                    )
                }
            }

            EmberSectionHeader(stringResource(StringRes.server_detail_section_connection))
            EmberGroupCard {
                EmberSettingRow(
                    title = stringResource(StringRes.server_detail_name),
                    subtitle = server.name,
                    onClick = { dialog = DetailDialog.Rename },
                    enabled = actionsEnabled,
                    trailing = { EmberChevron() },
                )
                if (server.type != ServerType.ParrotCloud) {
                    EmberRowDivider()
                    EmberSettingRow(
                        title = stringResource(StringRes.server_detail_address),
                        subtitle = server.baseUrl,
                        onClick = { dialog = DetailDialog.Address },
                        enabled = actionsEnabled,
                        trailing = { EmberChevron() },
                    )
                }
            }

            EmberSectionHeader(stringResource(StringRes.server_detail_section_account))
            EmberGroupCard {
                EmberSettingRow(
                    title = stringResource(StringRes.server_detail_signed_in_as),
                    subtitle = (status as? ServerStatus.Connected)?.account
                        ?: stringResource(StringRes.server_detail_not_signed_in),
                )
                if (isConnected) {
                    EmberRowDivider()
                    EmberSettingRow(
                        title = stringResource(StringRes.server_detail_sign_out),
                        subtitle = stringResource(StringRes.server_detail_sign_out_hint),
                        onClick = { dialog = DetailDialog.SignOut },
                        enabled = actionsEnabled,
                    )
                } else if (server.type == ServerType.ParrotCloud) {
                    EmberRowDivider()
                    EmberSettingRow(
                        title = stringResource(StringRes.servers_open_sync_backup),
                        subtitle = null,
                        onClick = onOpenSyncAndBackup,
                        enabled = actionsEnabled,
                        trailing = { EmberChevron() },
                    )
                } else if (server.type.canSignInFromServers()) {
                    EmberRowDivider()
                    EmberSettingRow(
                        title = stringResource(StringRes.server_detail_sign_in),
                        subtitle = null,
                        onClick = {
                            intentDispatcher(
                                ServerManagementIntent.OnLoginClick(
                                    serverId = server.id,
                                    serverType = server.type,
                                    isRetry = status.isProblem,
                                ),
                            )
                        },
                        enabled = actionsEnabled,
                    )
                }
            }

            EmberSectionHeader(stringResource(StringRes.server_detail_section_remove))
            EmberGroupCard {
                EmberSettingRow(
                    title = stringResource(StringRes.server_detail_remove),
                    subtitle = stringResource(StringRes.server_detail_remove_hint),
                    onClick = { dialog = DetailDialog.Remove },
                    isDestructive = true,
                    enabled = actionsEnabled,
                )
            }
        }
    }

    when (dialog) {
        DetailDialog.Rename -> TextEditDialog(
            title = stringResource(StringRes.server_detail_edit_name_title),
            initialValue = server.name,
            label = stringResource(StringRes.server_detail_name),
            validate = { value -> value.isNotBlank() },
            errorText = null,
            onSave = { value ->
                intentDispatcher(ServerManagementIntent.OnRenameServer(server.id, value))
            },
            onDismiss = { dialog = null },
        )

        DetailDialog.Address -> TextEditDialog(
            title = stringResource(StringRes.server_detail_edit_address_title),
            initialValue = server.baseUrl,
            label = stringResource(StringRes.server_detail_address),
            validate = ::isValidServerAddress,
            errorText = stringResource(StringRes.server_detail_address_error),
            onSave = { value ->
                intentDispatcher(ServerManagementIntent.OnChangeAddress(server.id, value))
            },
            onDismiss = { dialog = null },
        )

        DetailDialog.SignOut -> ConfirmDialog(
            title = stringResource(StringRes.server_detail_sign_out_title, server.name),
            body = stringResource(StringRes.server_detail_sign_out_body, host),
            confirmLabel = stringResource(StringRes.server_detail_sign_out),
            onConfirm = {
                intentDispatcher(ServerManagementIntent.OnLogoutClick(server.id, server.type))
            },
            onDismiss = { dialog = null },
        )

        DetailDialog.Remove -> ConfirmDialog(
            title = stringResource(StringRes.server_detail_remove_title, server.name),
            body = stringResource(StringRes.server_detail_remove_body, host),
            confirmLabel = stringResource(StringRes.server_detail_remove_confirm),
            isDestructive = true,
            onConfirm = {
                intentDispatcher(ServerManagementIntent.OnRemoveClick(server.id, server.type))
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false,
) {
    val colors = Ember.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = title, style = Ember.type.cardTitle.copy(fontSize = 22.sp), color = colors.ink)
        },
        text = {
            Text(
                text = body,
                style = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 24.sp),
                color = colors.ink2,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(StringRes.general_cancel),
                    color = colors.ink,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) {
                Text(
                    text = confirmLabel,
                    color = if (isDestructive) colors.destructive else colors.accentText,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

@Composable
private fun TextEditDialog(
    title: String,
    initialValue: String,
    label: String,
    validate: (String) -> Boolean,
    errorText: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Ember.colors
    var value by remember { mutableStateOf(initialValue) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = title, style = Ember.type.cardTitle.copy(fontSize = 22.sp), color = colors.ink)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { newValue ->
                        value = newValue
                        showError = false
                    },
                    label = { Text(label) },
                    singleLine = true,
                    isError = showError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError && errorText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        style = Ember.type.meta.copy(fontSize = 13.sp),
                        color = colors.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(StringRes.general_cancel),
                    color = colors.ink,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (validate(value)) {
                        onSave(value)
                        onDismiss()
                    } else {
                        showError = true
                    }
                },
            ) {
                Text(
                    text = stringResource(StringRes.server_detail_save),
                    color = colors.accentText,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}
