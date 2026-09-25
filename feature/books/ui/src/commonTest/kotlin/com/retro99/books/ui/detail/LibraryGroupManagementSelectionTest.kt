package com.retro99.books.ui.detail

import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.server.api.library.SourceAccountIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class LibraryGroupManagementSelectionTest {
    @Test
    fun mergeSelectionUsesStableMemberKeysAndTheDisplayedGroupId() {
        // Given
        val groupId = LibraryGroupId("displayed-group")
        val group = group(groupId, memberCount = 2)

        // When
        val selections = group.toManualMergeSelections()

        // Then
        assertEquals(group.members.map { member -> member.sourceKey }, selections.map { it.sourceKey })
        assertEquals(
            listOf(groupId, groupId),
            selections.map { selection -> selection.expectedGroupId },
        )
    }

    @Test
    fun splitRequiresASelectedProperSubsetFromTheCurrentGroup() {
        // Given
        val group = group(LibraryGroupId("displayed-group"), memberCount = 2)
        val viewState = LibraryGroupDetailViewState(
            group = group,
            isManagingMembers = true,
            managementGroupId = group.groupId,
        )

        // When
        val oneSelected = viewState.copy(
            selectedMemberKeys = setOf(group.members.first().sourceKey),
        )
        val allSelected = viewState.copy(
            selectedMemberKeys = group.members.map { member -> member.sourceKey }.toSet(),
        )
        val staleGroup = viewState.copy(
            managementGroupId = LibraryGroupId("retired-group"),
            selectedMemberKeys = setOf(group.members.first().sourceKey),
        )

        // Then
        assertTrue(oneSelected.canSplitSelectedMembers)
        assertFalse(oneSelected.selectedAllMembers)
        assertTrue(allSelected.selectedAllMembers)
        assertFalse(allSelected.canSplitSelectedMembers)
        assertFalse(staleGroup.canSplitSelectedMembers)
    }

    private fun group(groupId: LibraryGroupId, memberCount: Int): LibraryBookGroup {
        val profileId = LibraryProfileId("profile")
        val members = (1..memberCount).map { index ->
            val connectionId = SourceConnectionId("connection-$index")
            val sourceKey = SourceBookKey(
                profileId = profileId,
                adapterId = LibraryAdapterId("adapter"),
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("book-$index"),
            )
            LibraryGroupMember(
                snapshot = SourceBookSnapshot(
                    source = SourceBookRef(sourceKey, connectionId),
                    metadata = SourceBookMetadata("Book $index"),
                    resources = emptyList(),
                    status = SourceSnapshotStatus(
                        observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                        presence = SourcePresence.Present,
                        isAuthoritative = false,
                    ),
                ),
                membershipOrigin = LibraryMembershipOrigin.Manual,
                membershipRevision = 1,
                decisionId = null,
            )
        }
        return LibraryBookGroup(
            profileId = profileId,
            groupId = groupId,
            displayMetadata = SourceBookMetadata("Grouped title"),
            members = members,
        )
    }
}
