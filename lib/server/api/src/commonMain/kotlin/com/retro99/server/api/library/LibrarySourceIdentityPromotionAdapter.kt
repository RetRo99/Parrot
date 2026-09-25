package com.retro99.server.api.library

/**
 * Optional source-adapter capability for promoting retained unresolved memberships after a
 * trustworthy portable account identity becomes available.
 */
interface LibrarySourceIdentityPromotionAdapter {
    /**
     * Return the portable form of [unresolvedSource] when the adapter can prove that mapping.
     * Return `null` when the native identity cannot be recovered safely. The returned reference
     * must retain the source profile, adapter, and connection and use [portableAccountIdentity].
     * An adapter may change the native book ID only when its identity contract proves that change.
     */
    fun promoteUnresolvedSourceIdentity(
        unresolvedSource: SourceBookRef,
        portableAccountIdentity: SourceAccountIdentity.Portable,
    ): SourceBookRef?
}
