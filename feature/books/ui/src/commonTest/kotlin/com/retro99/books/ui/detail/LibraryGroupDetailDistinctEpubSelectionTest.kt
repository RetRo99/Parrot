package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookMemberProgressDomainModel
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.library.domain.projection.readerTargetFor
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LibraryGroupDetailDistinctEpubSelectionTest {
    @Test
    fun manuallyMergedEpubsKeepSeparateReaderTargetsProgressAndProgressOwners() {
        // Given
        val firstMember = member("a", "1")
        val secondMember = member("b", "2")
        val group = LibraryBookGroup(
            profileId = PROFILE_ID,
            groupId = GROUP_ID,
            displayMetadata = SourceBookMetadata("Merged EPUBs"),
            members = listOf(firstMember, secondMember),
        )
        val groupedBook = BookWithProgressDomainModel(
            book = BookDomainModel.LocalBook(
                uuid = "fixture-a",
                serverId = CONNECTION_ID.value,
                serverType = ServerType.Local,
                title = "Merged EPUBs",
                description = null,
                coverUrl = null,
                author = null,
                filePath = "/books/fixture-a.epub",
                fileSize = 1L,
                importedAt = "2026-09-25T00:00:00Z",
                lastOpenedAt = null,
                bookType = BookType.EBOOK,
                publicationDate = null,
                unifiedGroupId = GROUP_ID.value,
                groupMemberUuids = listOf("fixture-a", "fixture-b"),
            ),
            progressInfo = progress("fixture-a", 0.23),
            memberProgress = listOf(
                memberProgress(firstMember, "fixture-a", 0.23),
                memberProgress(secondMember, "fixture-b", 0.76),
            ),
        )

        // When
        val readerTargets = group.members.flatMap { member ->
            member.snapshot.resources.mapNotNull { resource ->
                member.readerTargetFor(resource)
            }
        }
        val defaultMediaTarget = requireNotNull(group.defaultMediaTargets(emptyList())["ebook"])
        val progressBySource = projectLibraryGroupReadingProgress(group, listOf(groupedBook))

        // Then
        assertEquals(2, readerTargets.size)
        assertEquals(
            setOf("fixture-a", "fixture-b"),
            readerTargets.map { target -> target.nativeBookId }.toSet(),
        )
        assertEquals(
            setOf("/books/fixture-a.epub", "/books/fixture-b.epub"),
            readerTargets.map { target -> target.storage.value }.toSet(),
        )
        assertEquals(
            setOf("fixture-a", "fixture-b"),
            readerTargets.map { target -> target.resource.nativeResourceId }.toSet(),
        )
        assertEquals(
            setOf("fixture-a", "fixture-b"),
            readerTargets.map { target -> target.progressOwner.nativeProgressId }.toSet(),
        )
        assertNotEquals(
            readerTargets[0].progressOwner,
            readerTargets[1].progressOwner,
        )
        assertEquals(1, readerTargets.count { target ->
            target.resource == defaultMediaTarget.resource.reference
        })
        assertEquals(
            0.23,
            readingProgressForResource(progressBySource, firstMember.sourceKey, "ebook"),
        )
        assertEquals(
            0.76,
            readingProgressForResource(progressBySource, secondMember.sourceKey, "ebook"),
        )
        assertTrue(readerTargets.any { target ->
            target.resource != defaultMediaTarget.resource.reference
        })
    }

    private fun member(suffix: String, hashDigit: String): LibraryGroupMember {
        val sourceKey = SourceBookKey(
            profileId = PROFILE_ID,
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                backendId = LocalContentIdentity.BACKEND_ID,
                accountId = LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(hashDigit.repeat(64)),
        )
        val source = SourceBookRef(sourceKey, CONNECTION_ID)
        val resource = SourceMediaResource(
            reference = SourceResourceRef(sourceKey, "fixture-$suffix"),
            mediaType = BookType.EBOOK.value,
            format = "epub",
            availability = SourceResourceAvailability.DevicePresent,
            localStorageReference = DeviceStorageRef("/books/fixture-$suffix.epub"),
        )
        return LibraryGroupMember(
            snapshot = SourceBookSnapshot(
                source = source,
                metadata = SourceBookMetadata("Fixture $suffix"),
                resources = listOf(resource),
                status = SourceSnapshotStatus(
                    observedAt = Instant.parse("2026-09-25T00:00:00Z"),
                    presence = SourcePresence.Present,
                    isAuthoritative = true,
                ),
            ),
            membershipOrigin = LibraryMembershipOrigin.Manual,
            membershipRevision = 1,
            decisionId = "manual-merge-fixtures-a-and-b",
        )
    }

    private fun memberProgress(
        member: LibraryGroupMember,
        bookUuid: String,
        progression: Double,
    ): BookMemberProgressDomainModel = BookMemberProgressDomainModel(
        sourceKey = member.sourceKey,
        serverId = CONNECTION_ID.value,
        bookUuid = bookUuid,
        mediaTypes = setOf(BookType.EBOOK.value),
        progressInfoByMediaType = mapOf(
            BookType.EBOOK.value to progress(bookUuid, progression),
        ),
        savedPositionUpdatedAtByMediaType = emptyMap(),
    )

    private fun progress(bookUuid: String, progression: Double): BookProgressInfoDomainModel =
        BookProgressInfoDomainModel(
            bookUuid = bookUuid,
            localProgression = progression,
            remoteProgression = null,
            isEbookCached = false,
            isAudiobookCached = false,
            isReadaloudCached = false,
        )

    private companion object {
        val PROFILE_ID = LibraryProfileId("profile")
        val GROUP_ID = LibraryGroupId("manual-merge-group")
        val CONNECTION_ID = SourceConnectionId("local-installation")
    }
}
