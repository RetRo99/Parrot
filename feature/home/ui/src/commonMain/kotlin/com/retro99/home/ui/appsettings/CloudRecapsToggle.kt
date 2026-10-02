package com.retro99.home.ui.appsettings

import com.retro99.cloudaccount.domain.model.CloudAuthState

/** Parrot Cloud sign-in as far as the Cloud recaps setting cares. */
enum class CloudRecapsAccess { Restoring, SignedIn, SignedOut }

// Only a live session counts, as for the recap runner: an expired
// or unrefreshable one can't call the recap function.
internal fun CloudAuthState.toCloudRecapsAccess(): CloudRecapsAccess = when (this) {
    CloudAuthState.RestoringSession -> CloudRecapsAccess.Restoring
    is CloudAuthState.SignedIn -> CloudRecapsAccess.SignedIn
    CloudAuthState.SignedOut,
    is CloudAuthState.AwaitingEmailVerification,
    is CloudAuthState.ReauthenticationRequired,
    is CloudAuthState.RefreshUnavailable,
    -> CloudRecapsAccess.SignedOut
}

data class CloudRecapsToggle(
    val checked: Boolean,
    val enabled: Boolean,
    val showSignInHint: Boolean,
)

/**
 * Turning on needs a Parrot Cloud session; turning off is always
 * allowed, so consent can be withdrawn while signed out.
 */
internal fun cloudRecapsToggle(consent: Boolean, access: CloudRecapsAccess) = CloudRecapsToggle(
    checked = consent,
    enabled = consent || access == CloudRecapsAccess.SignedIn,
    // No hint while restoring, so signed-in users see no flash.
    showSignInHint = access == CloudRecapsAccess.SignedOut,
)

internal fun canSetCloudRecaps(enabled: Boolean, access: CloudRecapsAccess): Boolean =
    !enabled || access == CloudRecapsAccess.SignedIn
