package com.retro99.library.domain.grouping

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceConnectionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryManualGroupingUseCaseTest {
    @Test
    fun mergeForwardsOnlyExplicitMembersAndTheirExpectedGroups() = runTest {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val members = listOf(
            LibraryGroupMemberSelection(
                sourceKey("profile-a", "first"),
                LibraryGroupId("group-a"),
            ),
            LibraryGroupMemberSelection(
                sourceKey("profile-a", "second"),
                LibraryGroupId("group-b"),
            ),
        )
        val repository = RecordingManualGroupingRepository()
        val classUnderTest = MergeLibraryGroupMembersUseCase(repository)

        // When
        val result = classUnderTest(profileId, members)

        // Then
        assertEquals(LibraryGroupId("merged-group"), result)
        assertEquals(profileId, repository.mergeProfileId)
        assertEquals(members, repository.mergeMembers)
    }

    @Test
    fun mergeRejectsCrossProfileAndDuplicateMembersBeforeRepositoryCall() = runTest {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val source = sourceKey("profile-a", "first")
        val repository = RecordingManualGroupingRepository()
        val classUnderTest = MergeLibraryGroupMembersUseCase(repository)

        // When / Then
        assertFailsWith<IllegalArgumentException> {
            classUnderTest(
                profileId,
                listOf(
                    LibraryGroupMemberSelection(source, LibraryGroupId("group-a")),
                    LibraryGroupMemberSelection(
                        sourceKey("profile-b", "second"),
                        LibraryGroupId("group-b"),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            classUnderTest(
                profileId,
                listOf(
                    LibraryGroupMemberSelection(source, LibraryGroupId("group-a")),
                    LibraryGroupMemberSelection(source, LibraryGroupId("group-b")),
                ),
            )
        }
        assertEquals(null, repository.mergeMembers)
    }

    @Test
    fun splitForwardsTheNamedMembersAndExpectedSourceGroup() = runTest {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val groupId = LibraryGroupId("group-a")
        val movedMembers = listOf(sourceKey("profile-a", "first"))
        val repository = RecordingManualGroupingRepository()
        val classUnderTest = SplitLibraryGroupMembersUseCase(repository)

        // When
        val result = classUnderTest(profileId, groupId, movedMembers)

        // Then
        assertEquals(LibraryGroupId("split-group"), result)
        assertEquals(profileId, repository.splitProfileId)
        assertEquals(groupId, repository.splitGroupId)
        assertEquals(movedMembers, repository.splitMembers)
    }

    @Test
    fun splitRejectsMembersFromAnotherProfileBeforeRepositoryCall() = runTest {
        // Given
        val repository = RecordingManualGroupingRepository()
        val classUnderTest = SplitLibraryGroupMembersUseCase(repository)

        // When / Then
        assertFailsWith<IllegalArgumentException> {
            classUnderTest(
                LibraryProfileId("profile-a"),
                LibraryGroupId("group-a"),
                listOf(sourceKey("profile-b", "other-profile")),
            )
        }
        assertEquals(null, repository.splitMembers)
    }

    private fun sourceKey(profileId: String, nativeBookId: String) = SourceBookKey(
        profileId = LibraryProfileId(profileId),
        adapterId = LibraryAdapterId("adapter-a"),
        accountIdentity = SourceAccountIdentity.Unresolved(SourceConnectionId("connection-a")),
        nativeBookId = NativeBookId(nativeBookId),
    )

    private class RecordingManualGroupingRepository : LibraryManualGroupingRepository {
        var mergeProfileId: LibraryProfileId? = null
        var mergeMembers: List<LibraryGroupMemberSelection>? = null
        var preferredMetadataSourceKey: SourceBookKey? = null
        var splitProfileId: LibraryProfileId? = null
        var splitGroupId: LibraryGroupId? = null
        var splitMembers: List<SourceBookKey>? = null

        override suspend fun mergeMembers(
            profileId: LibraryProfileId,
            members: List<LibraryGroupMemberSelection>,
            preferredMetadataSourceKey: SourceBookKey?,
        ): LibraryGroupId {
            mergeProfileId = profileId
            mergeMembers = members
            this.preferredMetadataSourceKey = preferredMetadataSourceKey
            return LibraryGroupId("merged-group")
        }

        override suspend fun splitMembers(
            profileId: LibraryProfileId,
            sourceGroupId: LibraryGroupId,
            movedSourceKeys: List<SourceBookKey>,
        ): LibraryGroupId {
            splitProfileId = profileId
            splitGroupId = sourceGroupId
            splitMembers = movedSourceKeys
            return LibraryGroupId("split-group")
        }
    }
}
