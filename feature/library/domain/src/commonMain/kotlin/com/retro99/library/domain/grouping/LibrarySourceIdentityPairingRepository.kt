package com.retro99.library.domain.grouping

import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import kotlinx.coroutines.flow.Flow

interface LibrarySourceIdentityPairingRepository {
    fun observeAudiobookshelfPairingStatus(
        profileId: LibraryProfileId,
        serverId: String,
    ): Flow<LibrarySourceIdentityPairingStatus>

    suspend fun createAudiobookshelfPairingCode(
        profileId: LibraryProfileId,
        serverId: String,
    ): AudiobookshelfPairingResult

    suspend fun bindAudiobookshelfPairingCode(
        profileId: LibraryProfileId,
        serverId: String,
        code: String,
    ): AudiobookshelfPairingResult

    suspend fun revokeAudiobookshelfPairing(
        profileId: LibraryProfileId,
        serverId: String,
    ): AudiobookshelfPairingResult
}

data class AudiobookshelfPairingResult(
    val status: AudiobookshelfPairingActionStatus,
    val code: String? = null,
    val conflictingNativeBookIds: Set<NativeBookId> = emptySet(),
    val blockingMembershipCount: Int = 0,
)

enum class AudiobookshelfPairingActionStatus {
    Completed,
    CloudAccountRequired,
    CloudAccountMismatch,
    SourceAccountMismatch,
    SourceAccountIdUnavailable,
    AlreadyPaired,
    InvalidCode,
    UnsupportedCodeVersion,
    NotAuthenticated,
    ServerUnavailable,
    HasSharedMemberships,
}
