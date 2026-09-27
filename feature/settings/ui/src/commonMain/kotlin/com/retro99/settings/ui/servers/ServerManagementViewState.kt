package com.retro99.settings.ui.servers

import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.server.api.ServerType
import com.retro99.analytics.api.ServerManagementAnalyticsEvent

data class ServerManagementOperationFailure(
    val serverId: String,
    val serverType: ServerType,
    val operation: ServerManagementAnalyticsEvent.Operation,
)

data class ServerManagementViewState(
    val isLoading: Boolean = true,
    val servers: List<ServerWithStatusUiModel> = emptyList(),
    val isOperationInProgress: Boolean = false,
    val operationFailure: ServerManagementOperationFailure? = null,
)
