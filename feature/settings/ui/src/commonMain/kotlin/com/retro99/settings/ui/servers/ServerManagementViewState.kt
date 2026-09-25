package com.retro99.settings.ui.servers

import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.library.domain.grouping.AudiobookshelfPairingActionStatus
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel

data class ServerManagementViewState(
    val isLoading: Boolean = true,
    val servers: List<ServerWithStatusUiModel> = emptyList(),
    val localProfileId: String = "default",
    val cloudAuthState: CloudAuthState = CloudAuthState.RestoringSession,
    val pairingStatuses: Map<String, LibrarySourceIdentityPairingStatus> = emptyMap(),
    val pairingServerId: String? = null,
    val pairingCodeInput: String = "",
    val displayedPairingCode: String? = null,
    val pairingActionStatus: AudiobookshelfPairingActionStatus? = null,
    val pairingAction: PairingAction? = null,
    val pairingConflictCount: Int = 0,
    val pairingUnexpectedError: Boolean = false,
    val isPairingActionInProgress: Boolean = false,
)

enum class PairingAction {
    CreateCode,
    ImportCode,
    ShowCode,
    Unpair,
}
