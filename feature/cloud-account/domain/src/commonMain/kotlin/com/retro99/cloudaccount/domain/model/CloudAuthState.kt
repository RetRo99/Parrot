package com.retro99.cloudaccount.domain.model

sealed interface CloudAuthState {
    data object RestoringSession : CloudAuthState

    data object SignedOut : CloudAuthState

    data class AwaitingEmailVerification(
        val email: String,
    ) : CloudAuthState

    data class SignedIn(
        val account: CloudAccount,
    ) : CloudAuthState

    data class ReauthenticationRequired(
        val account: CloudAccount,
    ) : CloudAuthState

    data class RefreshUnavailable(
        val account: CloudAccount,
    ) : CloudAuthState
}
