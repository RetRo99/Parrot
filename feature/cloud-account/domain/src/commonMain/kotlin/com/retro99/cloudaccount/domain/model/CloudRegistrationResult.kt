package com.retro99.cloudaccount.domain.model

sealed interface CloudRegistrationResult {
    data class SignedIn(
        val account: CloudAccount,
    ) : CloudRegistrationResult

    data class AwaitingEmailVerification(
        val email: String,
    ) : CloudRegistrationResult
}
