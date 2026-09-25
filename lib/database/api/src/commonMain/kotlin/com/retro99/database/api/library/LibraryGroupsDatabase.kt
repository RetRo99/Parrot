package com.retro99.database.api.library

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import kotlinx.coroutines.flow.Flow

data class LibraryGroupRecord(
    val profileId: LibraryProfileId,
    val groupId: LibraryGroupId,
    val createdAt: String,
    val preferredMetadataSourceKey: SourceBookKey? = null,
    val preferredMediaSourceKeys: Map<String, SourceBookKey> = emptyMap(),
)

data class LibraryGroupMembershipRecord(
    val groupId: LibraryGroupId,
    val source: SourceBookRef,
    val origin: LibraryMembershipOrigin = LibraryMembershipOrigin.Backfill,
    val revision: Long = 0L,
    val decisionId: String? = null,
)

data class LibraryAutomaticGroupMerge(
    val profileId: LibraryProfileId,
    val survivorGroupId: LibraryGroupId,
    val retiredGroupIds: Set<LibraryGroupId>,
    val sourceKeys: Set<SourceBookKey>,
    val evidenceIds: Set<String>,
    val appliedAt: String,
)

data class LibraryManualSeparationRecord(
    val first: SourceBookKey,
    val second: SourceBookKey,
    val decisionId: String,
    val createdAt: String,
)

data class LibraryManualGroupMemberSelection(
    val sourceKey: SourceBookKey,
    val expectedGroupId: LibraryGroupId,
)

enum class LibraryMediaPreferenceChangeResult {
    Applied,
    AlreadyPreferred,
    StaleMembership,
}

data class LibraryMediaPreferenceChange(
    val profileId: LibraryProfileId,
    val activeGroupId: LibraryGroupId,
    val mediaType: String,
    val memberSourceKey: SourceBookKey,
    val decisionId: String,
    val appliedAt: String,
    val outboxEntry: SyncOutboxEntry,
)

data class LibraryManualGroupMerge(
    val profileId: LibraryProfileId,
    val newGroupId: LibraryGroupId,
    val members: List<LibraryManualGroupMemberSelection>,
    val decisionId: String,
    val appliedAt: String,
    val preferredMetadataSourceKey: SourceBookKey? = null,
    val outboxEntry: SyncOutboxEntry? = null,
)

data class LibraryManualGroupSplit(
    val profileId: LibraryProfileId,
    val sourceGroupId: LibraryGroupId,
    val newGroupId: LibraryGroupId,
    val movedSourceKeys: List<SourceBookKey>,
    val decisionId: String,
    val appliedAt: String,
    val retainedSourceKeys: List<SourceBookKey> = emptyList(),
    val outboxEntry: SyncOutboxEntry? = null,
)

data class LibrarySynchronizedGroupDecision(
    val profileId: LibraryProfileId,
    val decisionId: String,
    val revision: Long,
    val operation: String,
    val targetGroupId: LibraryGroupId,
    val members: List<SourceBookKey>,
    val retainedGroupId: LibraryGroupId? = null,
    val retainedMembers: List<SourceBookKey> = emptyList(),
    val preferredMetadataSourceKey: SourceBookKey? = null,
    val preferredMediaSourceKeys: Map<String, SourceBookKey> = emptyMap(),
    val payload: String,
    val appliedAt: String,
)

interface LibraryGroupsDatabase {
    /** Emit when group membership or metadata preferences change for the profile. */
    fun observeProjectionChanges(profileId: LibraryProfileId): Flow<Unit>

    /** Return the existing group on replay; create group and membership atomically otherwise. */
    suspend fun ensureGroupForSource(
        source: SourceBookRef,
        proposedGroupId: LibraryGroupId,
        createdAt: String,
    ): LibraryGroupId

    suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupRecord?

    suspend fun getMembership(key: SourceBookKey): LibraryGroupMembershipRecord?

    suspend fun getMemberships(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): List<LibraryGroupMembershipRecord>

    suspend fun getAllMemberships(
        profileId: LibraryProfileId,
    ): List<LibraryGroupMembershipRecord>

    /** Apply a stale-safe automatic merge and its redirects in one profile transaction. */
    suspend fun applyAutomaticMerge(merge: LibraryAutomaticGroupMerge): Boolean

    /** Merge only the explicitly selected members and persist decision provenance atomically. */
    suspend fun applyManualMerge(merge: LibraryManualGroupMerge)

    /** Move selected members to a new group and separate them from the remaining members. */
    suspend fun applyManualSplit(split: LibraryManualGroupSplit)

    /** Store a preferred media member, its decision log entry, and its sync outbox atomically. */
    suspend fun applyMediaPreference(
        change: LibraryMediaPreferenceChange,
    ): LibraryMediaPreferenceChangeResult {
        throw UnsupportedOperationException("Media preference changes are not supported")
    }

    /**
     * Apply a server-ordered decision once and retain portable memberships before
     * sources connect.
     */
    suspend fun applySynchronizedDecision(decision: LibrarySynchronizedGroupDecision): Boolean

    /** Attach the accepted server revision to a locally applied outbox decision. */
    suspend fun recordAcceptedDecision(
        profileId: LibraryProfileId,
        decisionId: String,
        revision: Long,
        payload: String,
    )

    /** Store an immutable redirect; conflicting or cyclic aliases are rejected. */
    suspend fun addGroupAlias(
        profileId: LibraryProfileId,
        aliasId: LibraryGroupId,
        targetId: LibraryGroupId,
        createdAt: String,
    )

    /** Follow redirects before group lookup or route resolution. */
    suspend fun resolveGroupId(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupId?

    /** An explicit separation survives source disappearance and prevents automatic rejoining. */
    suspend fun addManualSeparation(
        first: SourceBookKey,
        second: SourceBookKey,
        decisionId: String,
        createdAt: String,
    ): Boolean

    suspend fun getManualSeparations(
        profileId: LibraryProfileId,
    ): List<LibraryManualSeparationRecord>
}
