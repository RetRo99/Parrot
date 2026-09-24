package com.retro99.cloudaccount.domain.model

data class CloudProfileLink(
    val localProfileId: String,
    val cloudUserId: String,
    val syncEnabled: Boolean,
    val autoBackupEnabled: Boolean = false,
    val uploadAttestation: UploadAttestationRecord? = null,
)

fun CloudProfileLink?.isActiveFor(authState: CloudAuthState): Boolean {
    val signedIn = authState as? CloudAuthState.SignedIn ?: return false
    return this?.cloudUserId == signedIn.account.id
}
