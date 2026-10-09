package com.retro99.settings.ui.servers

import com.retro99.base.ui.BaseIntent
import com.retro99.server.api.ServerType

sealed interface ServerManagementIntent : BaseIntent {
    data class OnTurnOnCatalogue(val sourceId: String) : ServerManagementIntent
    data class OnRetryCatalogue(val sourceId: String) : ServerManagementIntent
    data object OnSignOutEverything : ServerManagementIntent
    data class OnLoginClick(
        val serverId: String,
        val serverType: ServerType,
        val isRetry: Boolean = false,
    ) : ServerManagementIntent
    data class OnLogoutClick(val serverId: String, val serverType: ServerType) : ServerManagementIntent
    data class OnRemoveClick(val serverId: String, val serverType: ServerType) : ServerManagementIntent
    data class OnRenameServer(val serverId: String, val name: String) : ServerManagementIntent
    data class OnChangeAddress(val serverId: String, val baseUrl: String) : ServerManagementIntent
    data object RetryFailedOperation : ServerManagementIntent
    data object DismissOperationFailure : ServerManagementIntent
    data object RetryServerListLoad : ServerManagementIntent
    data object OnAddServerClick : ServerManagementIntent
}
