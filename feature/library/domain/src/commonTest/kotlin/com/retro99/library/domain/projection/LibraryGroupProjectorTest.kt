package com.retro99.library.domain.projection

import com.retro99.library.domain.grouping.LibraryMembershipAssignment
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LibraryGroupProjectorTest {
    @Test
    fun projectionRetainsEverySourceAndUsesStableMetadataOrder() {
        val first = snapshot(adapterId = "a-source", title = "Stable title")
        val second = snapshot(adapterId = "z-source", title = "Other title")
        val groupId = LibraryGroupId("group-1")
        val memberships = listOf(
            LibraryMembershipAssignment(
                source = second.source.key,
                groupId = groupId,
                origin = LibraryMembershipOrigin.Automatic,
                revision = 2,
            ),
            LibraryMembershipAssignment(
                source = first.source.key,
                groupId = groupId,
                origin = LibraryMembershipOrigin.Backfill,
                revision = 1,
            ),
        )

        val firstProjection = LibraryGroupProjector.project(memberships, listOf(second, first))
        val replayProjection = LibraryGroupProjector.project(
            memberships.reversed(),
            listOf(first, second),
        )

        assertEquals(1, firstProjection.size)
        assertEquals(setOf(first.source.key, second.source.key),
            firstProjection.single().members.map { member -> member.sourceKey }.toSet())
        assertEquals("Stable title", firstProjection.single().displayMetadata.title)
        assertEquals(firstProjection, replayProjection)
        assertEquals(2, firstProjection.single().members.first { member ->
            member.sourceKey == second.source.key
        }.membershipRevision)
    }

    @Test
    fun projectionRetainsPreferredMediaSourcesByMediaType() {
        val first = snapshot(adapterId = "a-source", title = "Book")
        val preferred = snapshot(adapterId = "z-source", title = "Book")
        val groupId = LibraryGroupId("group-1")
        val memberships = listOf(first, preferred).map { sourceSnapshot ->
            LibraryMembershipAssignment(sourceSnapshot.source.key, groupId)
        }

        val group = LibraryGroupProjector.project(
            memberships = memberships,
            snapshots = listOf(first, preferred),
            preferredMediaSources = mapOf(
                groupId to mapOf("ebook" to preferred.source.key),
            ),
        ).single()

        assertEquals(mapOf("ebook" to preferred.source.key), group.preferredMediaSourceKeys)
    }

    @Test
    fun unknownSourceRemainsInGroupAndKeepsItsResources() {
        val present = snapshot(adapterId = "a-source", title = "Book")
        val unknown = snapshot(
            adapterId = "b-source",
            title = "Book",
            presence = SourcePresence.Unknown,
        )
        val memberships = listOf(present, unknown).map { sourceSnapshot ->
            LibraryMembershipAssignment(sourceSnapshot.source.key, LibraryGroupId("group-1"))
        }

        val group = LibraryGroupProjector.project(memberships, listOf(present, unknown)).single()

        assertEquals(2, group.members.size)
        assertEquals(SourcePresence.Unknown, group.members.first { member ->
            member.sourceKey == unknown.source.key
        }.snapshot.status.presence)
        assertEquals(1, group.members.first { member ->
            member.sourceKey == unknown.source.key
        }.snapshot.resources.size)
    }

    @Test
    fun readerTargetUsesTheExactAvailableDeviceResource() {
        val sourceSnapshot = snapshot(adapterId = "local", title = "Book")
        val resource = sourceSnapshot.resources.single().copy(
            availability = SourceResourceAvailability.DevicePresent,
            localStorageReference = DeviceStorageRef("/books/book.epub"),
        )
        val member = LibraryGroupMember(
            snapshot = sourceSnapshot.copy(resources = listOf(resource)),
            membershipOrigin = LibraryMembershipOrigin.Backfill,
            membershipRevision = 0,
            decisionId = null,
        )

        val target = member.readerTargetFor(resource)

        assertEquals("connection-local", target?.connectionId)
        assertEquals("native-resource-local", target?.nativeBookId)
        assertEquals(resource.reference, target?.resource)
        assertEquals("ebook", target?.mediaType)
        assertEquals("Book", target?.title)
        assertEquals(DeviceStorageRef("/books/book.epub"), target?.storage)
        assertEquals(
            ProgressOwnerRef(
                adapterId = LibraryAdapterId("local"),
                source = sourceSnapshot.source,
                nativeProgressId = "native-resource-local",
            ),
            target?.progressOwner,
        )
    }

    @Test
    fun portableLocalIdentityStillResolvesReaderAndProgressByImportedUuid() {
        val connection = SourceConnectionId("local-installation")
        val sourceKey = SourceBookKey(
            profileId = LibraryProfileId("profile"),
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                LocalContentIdentity.BACKEND_ID,
                LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )
        val source = SourceBookRef(sourceKey, connection)
        val resource = SourceMediaResource(
            reference = SourceResourceRef(sourceKey, "imported-book-uuid"),
            mediaType = "ebook",
            availability = SourceResourceAvailability.DevicePresent,
            localStorageReference = DeviceStorageRef("/books/book.epub"),
        )
        val sourceSnapshot = SourceBookSnapshot(
            source = source,
            metadata = SourceBookMetadata("Book"),
            resources = listOf(resource),
            status = SourceSnapshotStatus(
                observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                presence = SourcePresence.Present,
                isAuthoritative = true,
            ),
        )
        val member = LibraryGroupMember(
            snapshot = sourceSnapshot,
            membershipOrigin = LibraryMembershipOrigin.Backfill,
            membershipRevision = 0,
            decisionId = null,
        )

        val target = member.readerTargetFor(resource)

        assertEquals("imported-book-uuid", target?.nativeBookId)
        assertEquals("imported-book-uuid", target?.progressOwner?.nativeProgressId)
        assertEquals(sourceKey, target?.progressOwner?.source?.key)
    }

    @Test
    fun readerTargetDoesNotLaunchWithoutADeviceStorageReference() {
        val sourceSnapshot = snapshot(adapterId = "local", title = "Book")
        val resource = sourceSnapshot.resources.single().copy(
            availability = SourceResourceAvailability.DevicePresent,
        )
        val member = LibraryGroupMember(
            snapshot = sourceSnapshot.copy(resources = listOf(resource)),
            membershipOrigin = LibraryMembershipOrigin.Backfill,
            membershipRevision = 0,
            decisionId = null,
        )

        assertNull(member.readerTargetFor(resource))
    }

    @Test
    fun readerTargetDoesNotLaunchFromUnknownOrRemoteAvailability() {
        val sourceSnapshot = snapshot(adapterId = "local", title = "Book")
        val deviceResource = sourceSnapshot.resources.single().copy(
            availability = SourceResourceAvailability.DevicePresent,
            localStorageReference = DeviceStorageRef("/books/book.epub"),
        )
        val staleMember = LibraryGroupMember(
            snapshot = sourceSnapshot.copy(
                resources = listOf(deviceResource),
                status = sourceSnapshot.status.copy(presence = SourcePresence.Unknown),
            ),
            membershipOrigin = LibraryMembershipOrigin.Backfill,
            membershipRevision = 0,
            decisionId = null,
        )
        val remoteResource = deviceResource.copy(
            availability = SourceResourceAvailability.AvailableRemotely,
            localStorageReference = null,
        )
        val remoteMember = LibraryGroupMember(
            snapshot = sourceSnapshot.copy(resources = listOf(remoteResource)),
            membershipOrigin = LibraryMembershipOrigin.Backfill,
            membershipRevision = 0,
            decisionId = null,
        )

        assertNull(staleMember.readerTargetFor(deviceResource))
        assertNull(remoteMember.readerTargetFor(remoteResource))
    }

    private fun snapshot(
        adapterId: String,
        title: String,
        presence: SourcePresence = SourcePresence.Present,
    ): SourceBookSnapshot {
        val connection = SourceConnectionId("connection-$adapterId")
        val source = SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId("profile"),
                adapterId = LibraryAdapterId(adapterId),
                accountIdentity = SourceAccountIdentity.Unresolved(connection),
                nativeBookId = NativeBookId("native-$adapterId"),
            ),
            connectionId = connection,
        )
        return SourceBookSnapshot(
            source = source,
            metadata = SourceBookMetadata(title),
            resources = listOf(
                SourceMediaResource(
                    reference = SourceResourceRef(source.key, "native-resource-$adapterId"),
                    mediaType = "ebook",
                ),
            ),
            status = SourceSnapshotStatus(
                observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                presence = presence,
                isAuthoritative = false,
            ),
        )
    }
}
