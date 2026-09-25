package com.retro99.books.domain.model

import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.server.api.MediaResource
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookSeries
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookCollection
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSeries
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class UnifiedServerBookTest {
    @Test
    fun resolvesPortableLocalMembershipToItsLiveImportedBook() {
        // Given
        val importedUuid = "imported-book-uuid"
        val connection = SourceConnectionId("local")
        val hash = "a".repeat(64)
        val localKey = SourceBookKey(
            profileId = LibraryProfileId("profile-a"),
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                backendId = LocalContentIdentity.BACKEND_ID,
                accountId = LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(hash),
        )
        val resource = SourceMediaResource(
            reference = SourceResourceRef(localKey, importedUuid),
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.DevicePresent,
            localStorageReference = DeviceStorageRef("/books/$importedUuid.epub"),
        )
        val localMember = LibraryGroupMember(
            snapshot = SourceBookSnapshot(
                source = SourceBookRef(
                    key = localKey,
                    connectionId = connection,
                ),
                metadata = metadata("Imported title", mediaTypes = listOf("ebook")),
                resources = listOf(resource),
                status = SourceSnapshotStatus(
                    observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                    presence = SourcePresence.Present,
                    isAuthoritative = true,
                ),
            ),
            membershipOrigin = LibraryMembershipOrigin.Automatic,
            membershipRevision = 1,
            decisionId = null,
        )
        val localBook = serverBook(
            uuid = importedUuid,
            serverId = connection.value,
            serverType = ServerType.Local,
        ).copy(
            isLocal = true,
            ebookFilepath = "/books/$importedUuid.epub",
            mediaResources = listOf(
                MediaResource(
                    mediaType = "ebook",
                    localPath = "/books/$importedUuid.epub",
                    nativeResourceId = importedUuid,
                    format = "epub",
                ),
            ),
        )

        // When
        val result = projectUnifiedServerBooks(
            groups = listOf(group("local-group", localMember)),
            feeds = listOf(
                LibraryBookSourceFeed(
                    adapterId = localKey.adapterId,
                    connectionId = connection.value,
                    books = listOf(localBook),
                ),
            ),
        )

        // Then
        assertEquals(1, result.size)
        assertEquals("local-group", result.single().groupId)
        assertEquals(importedUuid, result.single().book.uuid)
        assertEquals(listOf(importedUuid), result.single().memberUuids)
        assertTrue(result.single().book.isLocal)
    }

    @Test
    fun duplicateLocalImportsProjectAsOneGroupWithEveryImportedUuid() {
        // Given
        val connection = SourceConnectionId("local")
        val hash = "b".repeat(64)
        val localKey = SourceBookKey(
            profileId = LibraryProfileId("profile-a"),
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                backendId = LocalContentIdentity.BACKEND_ID,
                accountId = LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(hash),
        )
        val importedUuids = listOf("imported-a", "imported-b")
        val resources = importedUuids.map { importedUuid ->
            SourceMediaResource(
                reference = SourceResourceRef(localKey, importedUuid),
                mediaType = "ebook",
                format = "epub",
                availability = SourceResourceAvailability.DevicePresent,
                localStorageReference = DeviceStorageRef("/books/$importedUuid.epub"),
            )
        }
        val member = LibraryGroupMember(
            snapshot = SourceBookSnapshot(
                source = SourceBookRef(key = localKey, connectionId = connection),
                metadata = metadata("Duplicate import", mediaTypes = listOf("ebook")),
                resources = resources,
                status = SourceSnapshotStatus(
                    observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                    presence = SourcePresence.Present,
                    isAuthoritative = true,
                ),
            ),
            membershipOrigin = LibraryMembershipOrigin.Automatic,
            membershipRevision = 1,
            decisionId = null,
        )
        val localBooks = importedUuids.map { importedUuid ->
            serverBook(
                uuid = importedUuid,
                serverId = connection.value,
                serverType = ServerType.Local,
            ).copy(
                isLocal = true,
                libraryBookId = "${LocalContentIdentity.HASH_ALGORITHM}:$hash",
                ebookFilepath = "/books/$importedUuid.epub",
                mediaResources = listOf(
                    MediaResource(
                        mediaType = "ebook",
                        localPath = "/books/$importedUuid.epub",
                        nativeResourceId = importedUuid,
                        format = "epub",
                    ),
                ),
            )
        }
        val group = group("local-content-group", member)
        val feed = LibraryBookSourceFeed(
            adapterId = localKey.adapterId,
            connectionId = connection.value,
            books = localBooks,
        )

        // When
        val result = projectUnifiedServerBooks(groups = listOf(group), feeds = listOf(feed))
        val reversedResult = projectUnifiedServerBooks(
            groups = listOf(group),
            feeds = listOf(feed.copy(books = localBooks.reversed())),
        )

        // Then
        assertEquals(1, result.size)
        assertEquals("local-content-group", result.single().groupId)
        assertEquals("imported-a", result.single().book.uuid)
        assertEquals(importedUuids.toSet(), result.single().memberUuids.toSet())
        assertEquals(1, reversedResult.size)
        assertEquals("local-content-group", reversedResult.single().groupId)
        assertEquals("imported-a", reversedResult.single().book.uuid)
        assertEquals(importedUuids.toSet(), reversedResult.single().memberUuids.toSet())
    }

    @Test
    fun projectsOneGroupAndMergesSearchMetadataAcrossSources() {
        // Given
        val storytellerMember = member(
            adapterId = "storyteller",
            connectionId = "storyteller-1",
            nativeBookId = "storyteller-book",
            metadata = metadata(
                title = "Primary title",
                authors = listOf("Author one"),
                mediaTypes = listOf("ebook"),
            ),
        )
        val cloudMember = member(
            adapterId = "parrot-cloud",
            connectionId = "cloud-1",
            nativeBookId = "cloud-book",
            metadata = metadata(
                title = "Alternate title",
                authors = listOf("Author two"),
                series = listOf(SourceBookSeries("series-1", "Shared series", 1f)),
                tags = listOf("cloud-tag"),
                mediaTypes = listOf("audiobook"),
            ),
        )
        val preferredMediaSourceKeys = mapOf(
            "ebook" to storytellerMember.sourceKey,
            "audiobook" to cloudMember.sourceKey,
        )
        val group = group("group-one", storytellerMember, cloudMember).copy(
            preferredMediaSourceKeys = preferredMediaSourceKeys,
        )
        val storytellerBook = serverBook(
            uuid = "storyteller-book",
            serverId = "storyteller-1",
            serverType = ServerType.Storyteller,
        )
        val cloudBook = serverBook(
            uuid = "cloud-book",
            serverId = "cloud-1",
            serverType = ServerType.ParrotCloud,
        )

        // When
        val result = projectUnifiedServerBooks(
            groups = listOf(group),
            feeds = listOf(
                LibraryBookSourceFeed(
                    adapterId = LibraryAdapterId("storyteller"),
                    connectionId = "storyteller-1",
                    books = listOf(storytellerBook),
                ),
                LibraryBookSourceFeed(
                    adapterId = LibraryAdapterId("parrot-cloud"),
                    connectionId = "cloud-1",
                    books = listOf(cloudBook),
                ),
            ),
        )

        // Then
        assertEquals(1, result.size)
        assertEquals("group-one", result.single().groupId)
        assertEquals("Primary title", result.single().book.title)
        assertEquals("storyteller-1", result.single().book.serverId)
        assertEquals(listOf("cloud-book", "storyteller-book"), result.single().memberUuids.sorted())
        assertEquals(listOf("Alternate title"), result.single().alternateTitles)
        assertEquals(setOf("Author one", "Author two"), result.single().book.authors.toSet())
        assertEquals(setOf("Shared series"), result.single().book.series.map { it.name }.toSet())
        assertEquals(setOf("cloud-tag"), result.single().book.tags.toSet())
        assertEquals(setOf("ebook", "audiobook"), result.single().mediaTypes)
        assertEquals(preferredMediaSourceKeys, result.single().preferredMediaSourceKeys)
        assertEquals(
            setOf(storytellerMember.sourceKey, cloudMember.sourceKey),
            result.single().progressSources.mapNotNull { source -> source.sourceKey }.toSet(),
        )
        assertEquals(
            mapOf(
                storytellerMember.sourceKey to "storyteller-book",
                cloudMember.sourceKey to "cloud-book",
            ),
            result.single().progressSources.associate { source ->
                requireNotNull(source.sourceKey) to source.bookUuid
            },
        )
        assertTrue(result.single().book.hasEbook)
        assertTrue(result.single().book.hasAudiobook)
    }

    @Test
    fun localAndCloudProgressSourcesRetainTheSameExactLibraryBookId() {
        // Given
        val sharedLibraryBookId = "sha-256-v1:shared-local-cloud-copy"
        val localMember = member(
            adapterId = LocalContentIdentity.ADAPTER_ID,
            connectionId = "local",
            nativeBookId = "local-book",
            metadata = metadata("Shared copy", mediaTypes = listOf("ebook")),
        )
        val cloudMember = member(
            adapterId = "parrot-cloud",
            connectionId = "cloud",
            nativeBookId = "cloud-book",
            metadata = metadata("Shared copy", mediaTypes = listOf("ebook")),
        )
        val localBook = serverBook(
            uuid = "local-book",
            serverId = "local",
            serverType = ServerType.Local,
        ).copy(libraryBookId = sharedLibraryBookId)
        val cloudBook = serverBook(
            uuid = "cloud-book",
            serverId = "cloud",
            serverType = ServerType.ParrotCloud,
        ).copy(libraryBookId = sharedLibraryBookId)

        // When
        val result = projectUnifiedServerBooks(
            groups = listOf(group("local-cloud-copy", localMember, cloudMember)),
            feeds = listOf(
                LibraryBookSourceFeed(
                    adapterId = localMember.sourceKey.adapterId,
                    connectionId = "local",
                    books = listOf(localBook),
                ),
                LibraryBookSourceFeed(
                    adapterId = cloudMember.sourceKey.adapterId,
                    connectionId = "cloud",
                    books = listOf(cloudBook),
                ),
            ),
        ).single()

        // Then
        assertEquals(
            mapOf(
                localMember.sourceKey to sharedLibraryBookId,
                cloudMember.sourceKey to sharedLibraryBookId,
            ),
            result.progressSources.associate { source ->
                requireNotNull(source.sourceKey) to source.libraryBookId
            },
        )
    }

    @Test
    fun keepsCollectionsAssociatedWithTheirSourceMembership() {
        // Given
        val storytellerMember = member(
            adapterId = "storyteller",
            connectionId = "storyteller-1",
            nativeBookId = "storyteller-book",
            metadata = metadata("Title").copy(
                collections = listOf(
                    SourceBookCollection("storyteller-collection", "Storyteller list"),
                ),
            ),
        )
        val cloudMember = member(
            adapterId = "parrot-cloud",
            connectionId = "cloud-1",
            nativeBookId = "cloud-book",
            metadata = metadata("Title").copy(
                collections = listOf(SourceBookCollection("cloud-collection", "Cloud list")),
            ),
        )

        // When
        val result = projectUnifiedServerBooks(
            groups = listOf(group("group-one", storytellerMember, cloudMember)),
            feeds = emptyList(),
        ).single()

        // Then
        assertEquals(
            mapOf(
                storytellerMember.sourceKey to listOf(
                    SourceBookCollection("storyteller-collection", "Storyteller list"),
                ),
                cloudMember.sourceKey to listOf(
                    SourceBookCollection("cloud-collection", "Cloud list"),
                ),
            ),
            result.collectionsBySource,
        )
        assertEquals(emptyList(), result.book.collections)
    }

    @Test
    fun retainsAnUnavailableMemberAndItsMetadata() {
        // Given
        val activeMember = member(
            adapterId = "storyteller",
            connectionId = "storyteller-1",
            nativeBookId = "storyteller-book",
            metadata = metadata("Stable title", mediaTypes = listOf("ebook")),
        )
        val unavailableMember = member(
            adapterId = "audiobookshelf",
            connectionId = "abs-1",
            nativeBookId = "abs-book",
            metadata = metadata("Unavailable title", mediaTypes = listOf("audiobook")),
            presence = SourcePresence.Unknown,
        )

        // When
        val result = projectUnifiedServerBooks(
            groups = listOf(group("group-stale", activeMember, unavailableMember)),
            feeds = emptyList(),
        )

        // Then
        assertEquals(1, result.size)
        assertEquals("Stable title", result.single().book.title)
        assertEquals(setOf("abs-book", "storyteller-book"), result.single().memberUuids.toSet())
        assertEquals(listOf("Unavailable title"), result.single().alternateTitles)
        assertEquals(setOf("ebook", "audiobook"), result.single().mediaTypes)
        assertNull(result.single().book.serverType)
        assertEquals(
            setOf(ServerType.Audiobookshelf, ServerType.Storyteller),
            result.single().memberServerTypes,
        )
    }

    @Test
    fun omitsAGroupOnlyAfterEveryMemberWasAuthoritativelyRemoved() {
        // Given
        val removedMember = member(
            adapterId = "storyteller",
            connectionId = "storyteller-1",
            nativeBookId = "storyteller-book",
            metadata = metadata("Removed title"),
            presence = SourcePresence.Removed,
        )

        // When
        val result = projectUnifiedServerBooks(
            groups = listOf(group("removed-group", removedMember)),
            feeds = emptyList(),
        )

        // Then
        assertTrue(result.isEmpty())
    }

    private fun group(
        groupId: String,
        vararg members: LibraryGroupMember,
    ) = LibraryBookGroup(
        profileId = LibraryProfileId("profile-a"),
        groupId = LibraryGroupId(groupId),
        displayMetadata = members.first().snapshot.metadata,
        members = members.toList(),
    )

    private fun member(
        adapterId: String,
        connectionId: String,
        nativeBookId: String,
        metadata: SourceBookMetadata,
        presence: SourcePresence = SourcePresence.Present,
    ): LibraryGroupMember {
        val connection = SourceConnectionId(connectionId)
        val source = SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId("profile-a"),
                adapterId = LibraryAdapterId(adapterId),
                accountIdentity = SourceAccountIdentity.Unresolved(connection),
                nativeBookId = NativeBookId(nativeBookId),
            ),
            connectionId = connection,
        )
        return LibraryGroupMember(
            snapshot = SourceBookSnapshot(
                source = source,
                metadata = metadata,
                resources = emptyList(),
                status = SourceSnapshotStatus(
                    observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                    presence = presence,
                    isAuthoritative = presence != SourcePresence.Unknown,
                ),
            ),
            membershipOrigin = LibraryMembershipOrigin.Automatic,
            membershipRevision = 1,
            decisionId = null,
        )
    }

    private fun metadata(
        title: String,
        authors: List<String> = emptyList(),
        series: List<SourceBookSeries> = emptyList(),
        tags: List<String> = emptyList(),
        mediaTypes: List<String> = emptyList(),
    ) = SourceBookMetadata(
        title = title,
        authors = authors,
        series = series,
        tags = tags,
        mediaTypes = mediaTypes,
    )

    private fun serverBook(
        uuid: String,
        serverId: String,
        serverType: ServerType,
    ) = ServerBook(
        uuid = uuid,
        serverId = serverId,
        title = "Live title",
        description = null,
        coverUrl = null,
        authors = emptyList(),
        narrators = emptyList(),
        series = emptyList<ServerBookSeries>(),
        tags = emptyList(),
        hasEbook = false,
        hasAudiobook = false,
        hasReadaloud = false,
        serverType = serverType,
    )
}
