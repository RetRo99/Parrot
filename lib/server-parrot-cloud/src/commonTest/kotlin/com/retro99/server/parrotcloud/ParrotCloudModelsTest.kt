package com.retro99.server.parrotcloud

import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class ParrotCloudModelsTest {
    @Test
    fun incompleteCloudUploadsNeverBecomeAvailableRemoteReplicas() {
        val expectedAvailability = mapOf(
            "upload_pending" to RemoteFileAvailability.UploadPending,
            "uploading" to RemoteFileAvailability.Uploading,
            "upload_failed" to RemoteFileAvailability.UploadFailed,
        )

        expectedAvailability.forEach { (status, remoteAvailability) ->
            val book = libraryBook(status).toServerBook(
                serverId = "cloud-connection",
                localBook = null,
                fileStates = listOf(cloudFile(status)),
            )
            val snapshot = ParrotCloudLibrarySourceAdapter().snapshot(
                source(status),
                book,
                snapshotStatus(),
            )

            assertEquals(remoteAvailability, book.remoteFileAvailability, status)
            assertEquals(1, snapshot.resources.size, status)
            assertNotEquals(
                SourceResourceAvailability.AvailableRemotely,
                snapshot.resources.single().availability,
                status,
            )
            assertEquals(
                RemoteResourceRef("cloud-file-$status"),
                snapshot.resources.single().remoteResourceReference,
                status,
            )
        }
    }

    private fun libraryBook(status: String) = ParrotCloudLibraryBookEntity(
        libraryBookId = "library-book-$status",
        contentHash = "content-hash",
        contentHashAlgorithm = "sha-256-v1",
        title = "Cloud book",
        author = "Author",
        format = "ebook",
        cloudBookId = "cloud-book-$status",
    )

    private fun cloudFile(status: String) = CloudBookFileEntity(
        libraryBookId = "library-book-$status",
        cloudBookId = "cloud-book-$status",
        cloudBookFileId = "cloud-file-$status",
        mediaType = "ebook",
        relativePath = "book.epub",
        fileName = "book.epub",
        status = status,
        sizeBytes = 512L,
        contentHash = "content-hash",
        contentHashAlgorithm = "sha-256-v1",
        remoteRevision = 2L,
        updatedAt = "2026-09-25T00:00:00Z",
    )

    private fun source(status: String) = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId("profile"),
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "account"),
            nativeBookId = NativeBookId("cloud-book-$status"),
        ),
        connectionId = SourceConnectionId("cloud-connection"),
    )

    private fun snapshotStatus() = SourceSnapshotStatus(
        observedAt = Instant.parse("2026-09-25T00:00:00Z"),
        presence = SourcePresence.Present,
        isAuthoritative = true,
    )
}
