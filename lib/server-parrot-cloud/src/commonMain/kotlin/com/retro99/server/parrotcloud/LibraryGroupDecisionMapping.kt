package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibrarySynchronizedGroupDecision
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.sync.domain.LibraryGroupDecisionPayload
import com.retro99.sync.domain.LibraryGroupMemberRef

internal fun LibraryGroupDecisionPayload.toSynchronizedDecision(
    profileId: LibraryProfileId,
    revision: Long,
    encodedPayload: String,
): LibrarySynchronizedGroupDecision {
    fun toSourceBookKey(member: LibraryGroupMemberRef): SourceBookKey =
        LibraryGroupMemberRef.toSourceBookKey(profileId, member)

    return LibrarySynchronizedGroupDecision(
        profileId = profileId,
        decisionId = decisionId,
        revision = revision,
        operation = decisionType,
        targetGroupId = LibraryGroupId(targetGroupId),
        members = members.map { member -> toSourceBookKey(member) },
        retainedGroupId = retainedGroupId?.let(::LibraryGroupId),
        retainedMembers = retainedMembers.map { member -> toSourceBookKey(member) },
        preferredMetadataSourceKey = preferredMetadataMember?.let { member ->
            toSourceBookKey(member)
        },
        preferredMediaSourceKeys = preferredMediaMembers.mapValues { (_, member) ->
            toSourceBookKey(member)
        },
        payload = encodedPayload,
        appliedAt = createdAt,
    )
}
