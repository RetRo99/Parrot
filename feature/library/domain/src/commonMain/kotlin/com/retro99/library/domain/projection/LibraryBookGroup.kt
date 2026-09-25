package com.retro99.library.domain.projection

import com.retro99.library.domain.grouping.LibraryMembershipAssignment
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceAccountIdentity

data class LibraryGroupMember(
    val snapshot: SourceBookSnapshot,
    val membershipOrigin: LibraryMembershipOrigin,
    val membershipRevision: Long,
    val decisionId: String?,
) {
    val sourceKey: SourceBookKey
        get() = snapshot.source.key
}

/** Complete source membership survives display-metadata selection. */
data class LibraryBookGroup(
    val profileId: LibraryProfileId,
    val groupId: LibraryGroupId,
    val displayMetadata: SourceBookMetadata,
    val members: List<LibraryGroupMember>,
    val preferredMediaSourceKeys: Map<String, SourceBookKey> = emptyMap(),
)

object LibraryGroupProjector {
    fun project(
        memberships: List<LibraryMembershipAssignment>,
        snapshots: List<SourceBookSnapshot>,
        preferredMetadataSources: Map<LibraryGroupId, SourceBookKey> = emptyMap(),
        preferredMediaSources: Map<LibraryGroupId, Map<String, SourceBookKey>> = emptyMap(),
    ): List<LibraryBookGroup> {
        val membershipBySource = memberships.associateBy { membership -> membership.source }
        require(membershipBySource.size == memberships.size) {
            "A source membership can have only one current group"
        }
        val snapshotsBySource = snapshots.associateBy { snapshot -> snapshot.source.key }
        require(snapshotsBySource.size == snapshots.size) {
            "A source can have only one current snapshot"
        }

        val members = memberships.mapNotNull { membership ->
            val snapshot = snapshotsBySource[membership.source] ?: return@mapNotNull null
            require(snapshot.source.key.profileId == membership.source.profileId)
            LibraryGroupMember(
                snapshot = snapshot,
                membershipOrigin = membership.origin,
                membershipRevision = membership.revision,
                decisionId = membership.decisionId,
            )
        }

        return members.groupBy { member ->
            member.sourceKey.profileId to membershipBySource.getValue(member.sourceKey).groupId
        }.map { (identity, groupMembers) ->
            val orderedMembers = groupMembers.sortedBy { member -> member.sourceKey.stableSortKey() }
            val preferredSource = preferredMetadataSources[identity.second]
            val displayMetadata = orderedMembers.firstOrNull { member ->
                member.sourceKey == preferredSource
            }?.snapshot?.metadata ?: orderedMembers.first().snapshot.metadata
            LibraryBookGroup(
                profileId = identity.first,
                groupId = identity.second,
                displayMetadata = displayMetadata,
                members = orderedMembers,
                preferredMediaSourceKeys = preferredMediaSources[identity.second].orEmpty(),
            )
        }.sortedWith(
            compareBy<LibraryBookGroup> { group -> group.displayMetadata.title.lowercase() }
                .thenBy { group -> group.groupId.value },
        )
    }
}

private fun SourceBookKey.stableSortKey(): String {
    val account = when (val identity = accountIdentity) {
        is SourceAccountIdentity.Portable ->
            listOf("portable", identity.backendId, identity.accountId)
        is SourceAccountIdentity.Unresolved ->
            listOf("unresolved", identity.connectionId.value)
    }
    return (listOf(profileId.value, adapterId.value) + account + nativeBookId.value)
        .joinToString("|") { value -> "${value.length}:$value" }
}
