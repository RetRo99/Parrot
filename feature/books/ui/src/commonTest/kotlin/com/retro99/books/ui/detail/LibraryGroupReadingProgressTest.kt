package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookMemberProgressDomainModel
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.BookWithProgressDomainModel
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
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LibraryGroupReadingProgressTest {
    @Test
    fun eachMemberAndMediaResourceUsesItsOwnProgress() {
        // Given
        val firstMember = member("connection-one", "book-one")
        val secondMember = member("connection-two", "book-two")
        val group = LibraryBookGroup(
            profileId = PROFILE_ID,
            groupId = GROUP_ID,
            displayMetadata = SourceBookMetadata("Grouped book"),
            members = listOf(firstMember, secondMember),
        )
        val firstProgress = BookMemberProgressDomainModel(
            sourceKey = firstMember.sourceKey,
            serverId = "connection-one",
            bookUuid = "book-one",
            mediaTypes = setOf("ebook", "audiobook"),
            progressInfoByMediaType = mapOf(
                "EBOOK" to progress("book-one", 0.23),
                "Audiobook" to progress("book-one", 0.81),
            ),
            savedPositionUpdatedAtByMediaType = emptyMap(),
        )
        val secondProgress = BookMemberProgressDomainModel(
            sourceKey = secondMember.sourceKey,
            serverId = "connection-two",
            bookUuid = "book-two",
            mediaTypes = setOf("ebook", "audiobook"),
            progressInfoByMediaType = mapOf(
                "ebook" to progress("book-two", 0.46),
                "audiobook" to progress("book-two", 0.64),
            ),
            savedPositionUpdatedAtByMediaType = emptyMap(),
        )
        val groupedBook = BookWithProgressDomainModel(
            book = localBook(GROUP_ID.value),
            progressInfo = progress("book-one", 0.81),
            memberProgress = listOf(firstProgress, secondProgress),
        )
        val unrelatedGroupBook = BookWithProgressDomainModel(
            book = localBook("different-group"),
            progressInfo = progress("book-one", 1.0),
            memberProgress = listOf(
                firstProgress.copy(
                    progressInfoByMediaType = mapOf(
                        "ebook" to progress("book-one", 1.0),
                    ),
                ),
            ),
        )

        // When
        val progress = projectLibraryGroupReadingProgress(
            group = group,
            booksWithProgress = listOf(groupedBook, unrelatedGroupBook),
        )

        // Then
        assertEquals(
            0.23,
            readingProgressForResource(progress, firstMember.sourceKey, "ebook"),
        )
        assertEquals(
            0.81,
            readingProgressForResource(progress, firstMember.sourceKey, "AUDIOBOOK"),
        )
        assertEquals(
            0.46,
            readingProgressForResource(progress, secondMember.sourceKey, "ebook"),
        )
        assertEquals(
            0.64,
            readingProgressForResource(progress, secondMember.sourceKey, "audiobook"),
        )
    }

    private fun member(connectionId: String, bookUuid: String): LibraryGroupMember {
        val sourceKey = SourceBookKey(
            profileId = PROFILE_ID,
            adapterId = LibraryAdapterId(connectionId),
            accountIdentity = SourceAccountIdentity.Unresolved(
                SourceConnectionId(connectionId),
            ),
            nativeBookId = NativeBookId(bookUuid),
        )
        val source = SourceBookRef(sourceKey, SourceConnectionId(connectionId))
        val resources = listOf("ebook", "audiobook").map { mediaType ->
            SourceMediaResource(
                reference = SourceResourceRef(sourceKey, "$bookUuid-$mediaType"),
                mediaType = mediaType,
                availability = SourceResourceAvailability.Unknown,
            )
        }
        return LibraryGroupMember(
            snapshot = SourceBookSnapshot(
                source = source,
                metadata = SourceBookMetadata("Book $bookUuid"),
                resources = resources,
                status = SourceSnapshotStatus(
                    observedAt = Instant.parse("2026-09-25T00:00:00Z"),
                    presence = SourcePresence.Present,
                    isAuthoritative = false,
                ),
            ),
            membershipOrigin = LibraryMembershipOrigin.Automatic,
            membershipRevision = 1,
            decisionId = null,
        )
    }

    private fun localBook(groupId: String): BookDomainModel.LocalBook =
        BookDomainModel.LocalBook(
            uuid = "book-one",
            serverId = "local",
            serverType = ServerType.Local,
            title = "Grouped book",
            description = null,
            coverUrl = null,
            author = null,
            filePath = "/book.epub",
            fileSize = 1L,
            importedAt = "2026-09-25T00:00:00Z",
            lastOpenedAt = null,
            bookType = BookType.EBOOK,
            publicationDate = null,
            unifiedGroupId = groupId,
            groupMemberUuids = listOf("book-one", "book-two"),
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
        val GROUP_ID = LibraryGroupId("group")
    }
}
