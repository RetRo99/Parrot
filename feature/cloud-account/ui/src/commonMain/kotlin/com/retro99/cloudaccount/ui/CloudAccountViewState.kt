package com.retro99.cloudaccount.ui

import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.sync.domain.SyncStatus

data class CloudAccountViewState(
    val authState: CloudAuthState = CloudAuthState.RestoringSession,
    val profileLink: CloudProfileLink? = null,
    val mode: CloudAccountMode = CloudAccountMode.SignIn,
    val isLoading: Boolean = true,
    val isSubmitEnabled: Boolean = false,
    val showVerificationMessage: Boolean = false,
    val showLinkConfirmation: Boolean = false,
    val error: CloudAccountError? = null,
    val syncStatus: SyncStatus = SyncStatus.Idle,
)

enum class CloudAccountMode {
    SignIn,
    CreateAccount,
}

enum class CloudAccountError {
    ProfileAlreadyLinked,
    NotConfigured,
    Generic,
}
