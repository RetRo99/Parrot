package com.retro99.statistics.ui

import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap

/** What the session detail shows about the recap of one reading session. */
sealed interface SessionRecapUiState {
    data object Loading : SessionRecapUiState

    /** No recap was recorded, e.g. recaps were off when the session ran. */
    data class None(val cloudRecapsEnabled: Boolean) : SessionRecapUiState

    /** Too short, a reread or a repeat of the previous session. */
    data object Ineligible : SessionRecapUiState

    /** Recorded with consent, but consent is off now. */
    data object WaitingForOptIn : SessionRecapUiState

    data object Generating : SessionRecapUiState
    /** Captured input is waiting for automatic or explicit delivery. */
    data object Ready : SessionRecapUiState

    data class Succeeded(
        val summary: String,
        val engineId: String?,
        val model: String?,
    ) : SessionRecapUiState {
        override fun toString(): String = "Succeeded(chars=${summary.length}, engine=$engineId)"
    }

    /** The model found too little to summarise. */
    data object NotEnough : SessionRecapUiState

    data class FailedRetryable(val canRetry: Boolean) : SessionRecapUiState

    data class FailedPermanent(val canRetry: Boolean) : SessionRecapUiState

    data object SignInRequired : SessionRecapUiState
}

/**
 * [cloudRecapsEnabled] is the consent alone, whether or not a Parrot Cloud
 * session is usable; [engineAvailable] is consent and sign-in together, as the
 * job runner sees it. Waiting rows can only move once both hold, and the pair
 * is what tells "turn Cloud recaps on" apart from "sign in again".
 */
fun SessionRecap?.toSessionRecapUiState(
    cloudRecapsEnabled: Boolean,
    engineAvailable: Boolean,
): SessionRecapUiState {
    val recap = this ?: return SessionRecapUiState.None(cloudRecapsEnabled)
    return when (recap.status) {
        RecapStatus.SKIPPED_INELIGIBLE -> SessionRecapUiState.Ineligible
        RecapStatus.NOT_ENOUGH -> SessionRecapUiState.NotEnough
        RecapStatus.RUNNING, RecapStatus.CLOUD_QUEUED, RecapStatus.CLOUD_RUNNING -> SessionRecapUiState.Generating
        RecapStatus.SUCCEEDED -> recap.summary?.takeIf { it.isNotBlank() }
            ?.let { summary -> SessionRecapUiState.Succeeded(summary, recap.engineId, recap.model) }
            ?: SessionRecapUiState.FailedPermanent(canRetry = false)
        RecapStatus.FAILED_PERMANENT -> SessionRecapUiState.FailedPermanent(recap.canRetry)
        RecapStatus.CAPTURING,
        RecapStatus.PENDING,
        RecapStatus.FAILED_RETRYABLE,
        -> when {
            !cloudRecapsEnabled -> SessionRecapUiState.WaitingForOptIn
            !engineAvailable -> SessionRecapUiState.SignInRequired
            // The server refused the session even though it looks signed in.
            recap.status == RecapStatus.PENDING && recap.lastError == RecapErrorCode.AUTH_REQUIRED ->
                SessionRecapUiState.SignInRequired
            recap.status == RecapStatus.FAILED_RETRYABLE ->
                SessionRecapUiState.FailedRetryable(recap.canRetry)
            recap.status == RecapStatus.PENDING -> SessionRecapUiState.Ready
            else -> SessionRecapUiState.Generating
        }
    }
}
