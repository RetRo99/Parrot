package com.retro99.cloudaccount.ui

import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink

data class CloudAccountViewState(
    val authState: CloudAuthState = CloudAuthState.RestoringSession,
    val profileLink: CloudProfileLink? = null,
    val mode: CloudAccountMode = CloudAccountMode.SignIn,
    val isLoading: Boolean = true,
    val isSubmitEnabled: Boolean = false,
    val showVerificationMessage: Boolean = false,
    val showLinkConfirmation: Boolean = false,
    val error: CloudAccountError? = null,
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
