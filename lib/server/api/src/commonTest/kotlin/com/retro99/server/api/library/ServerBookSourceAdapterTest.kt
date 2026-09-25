package com.retro99.server.api.library

import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ServerBookSourceAdapterTest {
    private val adapter = object : ServerBookLibrarySourceAdapter() {
        override val adapterId = LibraryAdapterId("current-source")
    }

    @Test
    fun normalizesCurrentServerBookWithoutChangingItsSourceIdentity() {
        val source = source()
        val resource = SourceMediaResource(
            reference = SourceResourceRef(source.key, "native-file-1", "revision-2"),
            mediaType = "ebook",
            format = "epub",
            remoteResourceReference = RemoteResourceRef("adapter-owned-file-1"),
        )
        val record = ServerBookSourceRecord(
            source = source,
            book = book(),
            resources = listOf(resource),
            status = status(),
        )

        val snapshot = adapter.normalize(record)

        assertEquals(source, snapshot.source)
        assertEquals(listOf(resource), snapshot.resources)
        assertEquals("A Book", snapshot.metadata.title)
        assertEquals(
            listOf(SourceBookCollection("collection-1", "Favorite")),
            snapshot.metadata.collections,
        )
        assertEquals(emptyList(), snapshot.identityEvidence)
    }

    @Test
    fun snapshotKeepsNativeResourceIdsAndDoesNotInventIdsFromPaths() {
        val source = source()
        val serverBook = book().copy(
            mediaResources = listOf(
                MediaResource(
                    mediaType = "audiobook",
                    remoteAvailability = RemoteFileAvailability.Available,
                    size = 2048,
                    nativeResourceId = "audio-inode-1",
                    resourceRevision = "revision-1",
                    format = "audio/mpeg",
                ),
                MediaResource(
                    mediaType = "audiobook",
                    localPath = "/cache/audio-2.mp3",
                    remoteAvailability = RemoteFileAvailability.Available,
                ),
            ),
        )

        val snapshot = adapter.snapshot(source, serverBook, status())

        assertEquals(
            listOf(SourceBookCollection("collection-1", "Favorite")),
            snapshot.metadata.collections,
        )
        assertEquals(1, snapshot.resources.size)
        assertEquals("audio-inode-1", snapshot.resources.single().reference.nativeResourceId)
        assertEquals("revision-1", snapshot.resources.single().reference.revision)
        assertEquals(RemoteResourceRef("audio-inode-1"), snapshot.resources.single().remoteResourceReference)
        assertEquals(SourceResourceAvailability.AvailableRemotely, snapshot.resources.single().availability)
    }

    @Test
    fun doesNotInventCollectionsWhenTheSourceBookHasNone() {
        val snapshot = adapter.snapshot(source(), book().copy(collections = emptyList()), status())

        assertEquals(emptyList(), snapshot.metadata.collections)
    }

    @Test
    fun rejectsResourcesFromAnotherBook() {
        val source = source()
        val otherKey = source.key.copy(nativeBookId = NativeBookId("other"))

        assertFailsWith<IllegalArgumentException> {
            ServerBookSourceRecord(
                source = source,
                book = book(),
                resources = listOf(
                    SourceMediaResource(
                        reference = SourceResourceRef(otherKey, "file"),
                        mediaType = "ebook",
                    ),
                ),
                status = status(),
            )
        }
    }

    @Test
    fun rejectsRecordForAnotherAdapter() {
        val source = source().copy(
            key = source().key.copy(adapterId = LibraryAdapterId("different-adapter")),
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.normalize(
                ServerBookSourceRecord(source, book(), emptyList(), status = status()),
            )
        }
    }

    private fun source() = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId("profile"),
            adapterId = adapter.adapterId,
            accountIdentity = SourceAccountIdentity.Portable("backend", "account"),
            nativeBookId = NativeBookId("book-1"),
        ),
        connectionId = SourceConnectionId("connection"),
        legacyLibraryBookId = LegacyLibraryBookId("sha-256-v1:hash"),
    )

    private fun status() = SourceSnapshotStatus(
        observedAt = Instant.parse("2026-09-24T00:00:00Z"),
        presence = SourcePresence.Present,
        isAuthoritative = true,
    )

    private fun book() = ServerBook(
        uuid = "book-1",
        serverId = "connection",
        title = "A Book",
        description = "Description",
        coverUrl = null,
        authors = listOf("Author"),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        libraryBookId = "sha-256-v1:hash",
        contentHash = "hash",
        contentHashAlgorithm = "sha-256-v1",
        collections = listOf(ServerBookCollection("collection-1", "Favorite")),
    )
}
