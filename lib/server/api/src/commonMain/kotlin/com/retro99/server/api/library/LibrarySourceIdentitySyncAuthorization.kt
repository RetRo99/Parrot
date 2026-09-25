package com.retro99.server.api.library

/** Verifies that portable source identities are still bound to the Cloud account being synced. */
fun interface LibrarySourceIdentitySyncAuthorization {
    suspend fun isAuthorized(
        profileId: LibraryProfileId,
        cloudAccountId: String,
        adapterId: LibraryAdapterId,
        identity: SourceAccountIdentity.Portable,
    ): Boolean
}
