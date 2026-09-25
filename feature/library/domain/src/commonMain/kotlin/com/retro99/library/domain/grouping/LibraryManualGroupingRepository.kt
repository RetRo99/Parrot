package com.retro99.library.domain.grouping

import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey

data class LibraryGroupMemberSelection(
    val sourceKey: SourceBookKey,
    val expectedGroupId: LibraryGroupId,
)

interface LibraryManualGroupingRepository {
    suspend fun mergeMembers(
        profileId: LibraryProfileId,
        members: List<LibraryGroupMemberSelection>,
        preferredMetadataSourceKey: SourceBookKey? = null,
    ): LibraryGroupId

    suspend fun splitMembers(
        profileId: LibraryProfileId,
        sourceGroupId: LibraryGroupId,
        movedSourceKeys: List<SourceBookKey>,
    ): LibraryGroupId
}

class MergeLibraryGroupMembersUseCase(
    private val repository: LibraryManualGroupingRepository,
) {
    suspend operator fun invoke(
        profileId: LibraryProfileId,
        members: List<LibraryGroupMemberSelection>,
        preferredMetadataSourceKey: SourceBookKey? = null,
    ): LibraryGroupId {
        require(members.size >= 2) { "A manual merge needs at least two members" }
        require(members.map { member -> member.sourceKey }.distinct().size == members.size) {
            "A manual merge cannot name a source more than once"
        }
        require(members.all { member -> member.sourceKey.profileId == profileId }) {
            "A manual merge cannot cross profiles"
        }
        require(
            preferredMetadataSourceKey == null ||
                members.any { member -> member.sourceKey == preferredMetadataSourceKey },
        ) {
            "Preferred metadata must come from a selected merge member"
        }
        return repository.mergeMembers(profileId, members, preferredMetadataSourceKey)
    }
}

class SplitLibraryGroupMembersUseCase(
    private val repository: LibraryManualGroupingRepository,
) {
    suspend operator fun invoke(
        profileId: LibraryProfileId,
        sourceGroupId: LibraryGroupId,
        movedSourceKeys: List<SourceBookKey>,
    ): LibraryGroupId {
        require(movedSourceKeys.isNotEmpty()) { "A split needs at least one member" }
        require(movedSourceKeys.distinct().size == movedSourceKeys.size) {
            "A split cannot name a source more than once"
        }
        require(movedSourceKeys.all { sourceKey -> sourceKey.profileId == profileId }) {
            "A split cannot cross profiles"
        }
        return repository.splitMembers(profileId, sourceGroupId, movedSourceKeys)
    }
}
