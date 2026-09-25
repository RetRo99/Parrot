package com.retro99.books.ui.list

import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MergeMetadataSourceOptionTest {
    @Test
    fun optionsRetainSourceKeysAndDisplayMetadataForEachSelectedMember() {
        // Given
        val group = group()

        // When
        val options = group.toMergeMetadataSourceOptions()

        // Then
        assertEquals(
            group.members.map { member -> member.sourceKey },
            options.map { option -> option.sourceKey },
        )
        assertEquals(listOf("Book 1", "Book 2"), options.map { option -> option.title })
        assertEquals(
            listOf(listOf("Author 1"), listOf("Author 2")),
            options.map { option -> option.authors },
        )
        assertEquals(listOf("adapter-1", "adapter-2"), options.map { option -> option.adapterId })
    }

    private fun group(): LibraryBookGroup {
        val profileId = LibraryProfileId("profile")
        val members = (1..2).map { index ->
            val connectionId = SourceConnectionId("connection-$index")
            val sourceKey = SourceBookKey(
                profileId = profileId,
                adapterId = LibraryAdapterId("adapter-$index"),
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("book-$index"),
            )
            LibraryGroupMember(
                snapshot = SourceBookSnapshot(
                    source = SourceBookRef(sourceKey, connectionId),
                    metadata = SourceBookMetadata(
                        title = "Book $index",
                        authors = listOf("Author $index"),
                    ),
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
            groupId = LibraryGroupId("group"),
            displayMetadata = SourceBookMetadata("Book 1"),
            members = members,
        )
    }
}
