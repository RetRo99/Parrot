package com.retro99.database.api.library

import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId

enum class LibrarySourceIdentityPromotionStatus {
    Promoted,
    AlreadyPromoted,
    NotFound,
    Conflict,
}

data class LibrarySourceIdentityPromotionResult(
    val nativeBookId: String,
    val status: LibrarySourceIdentityPromotionStatus,
)

/** Rebinds an exact unresolved source after a trustworthy portable identity is available. */
interface LibrarySourceIdentityPromotionDatabase {
    /**
     * Promote one exact unresolved source to an adapter-certified portable reference. Adapters
     * with a content-derived identity may use a different portable native ID when their contract
     * validates that ID from verified whole-resource evidence.
     */
    suspend fun promoteUnresolvedSourceIdentity(
        unresolvedKey: SourceBookKey,
        portableSource: SourceBookRef,
    ): LibrarySourceIdentityPromotionStatus

    /** Resolve an exact previously promoted key for local history and route recovery. */
    suspend fun resolveSourceIdentityAlias(key: SourceBookKey): SourceBookKey?

    /**
     * Resolve an unresolved Cloud member only when its original connection and the currently
     * authenticated Cloud account match the persisted promotion.
     */
    suspend fun resolvePromotedParrotCloudSource(
        unresolvedKey: SourceBookKey,
        linkedConnectionId: SourceConnectionId,
        cloudUserId: String,
    ): SourceBookKey?

    /** Promote every unresolved Parrot Cloud member for this connection/account pair. */
    suspend fun promoteUnresolvedParrotCloudSources(
        profileId: LibraryProfileId,
        connectionId: SourceConnectionId,
        cloudUserId: String,
    ): List<LibrarySourceIdentityPromotionResult>
}
