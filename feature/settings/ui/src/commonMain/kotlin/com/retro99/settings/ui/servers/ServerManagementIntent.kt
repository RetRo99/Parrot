package com.retro99.settings.ui.servers

import com.retro99.base.ui.BaseIntent
import com.retro99.server.api.ServerType

sealed interface ServerManagementIntent : BaseIntent {
    data class OnLoginClick(
        val serverId: String,
        val serverType: ServerType,
        val isRetry: Boolean = false,
    ) : ServerManagementIntent
    data class OnLogoutClick(val serverId: String, val serverType: ServerType) : ServerManagementIntent
    data class OnRemoveClick(val serverId: String, val serverType: ServerType) : ServerManagementIntent
    data object RetryFailedOperation : ServerManagementIntent
    data object DismissOperationFailure : ServerManagementIntent
    data object OnAddServerClick : ServerManagementIntent
}
