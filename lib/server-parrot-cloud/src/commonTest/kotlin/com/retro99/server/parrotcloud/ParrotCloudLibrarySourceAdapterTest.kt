package com.retro99.server.parrotcloud

import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ParrotCloudLibrarySourceAdapterTest {
    private val adapter = ParrotCloudLibrarySourceAdapter()

    @Test
    fun exposesVerifiedBookHashWithoutInventingAResourceOrAvailability() {
        val hash = "verified-cloud-hash"
        val cloudBook = ParrotCloudLibraryBookEntity(
            libraryBookId = "sha-256-v1:$hash",
            contentHash = hash,
            contentHashAlgorithm = "sha-256-v1",
            title = "Cloud book",
            author = "Author",
            format = "ebook",
            cloudBookId = "cloud-book-id",
        ).toServerBook(
            serverId = "cloud-connection",
            localBook = null,
            fileStates = emptyList(),
        )

        val snapshot = adapter.snapshot(source(), cloudBook, status())

        assertTrue(snapshot.resources.isEmpty())
        val fingerprint = assertIs<SourceIdentityEvidence.BookFingerprint>(
            snapshot.identityEvidence.single(),
        )
        assertEquals(source().key, fingerprint.book)
        assertEquals("sha-256-v1", fingerprint.algorithm)
        assertEquals(hash, fingerprint.hash)
        assertEquals(FingerprintScope.WholeFile, fingerprint.scope)
        assertEquals(FingerprintVerification.Verified, fingerprint.verification)
    }

    @Test
    fun doesNotCertifyUnknownAlgorithmsAndUsesResourceEvidenceWhenAvailable() {
        val unknownAlgorithmSnapshot = adapter.snapshot(
            source(),
            book(contentHash = "opaque-hash", contentHashAlgorithm = "sha-256-v2"),
            status(),
        )
        assertTrue(unknownAlgorithmSnapshot.identityEvidence.isEmpty())

        val resourceHash = "verified-cloud-hash"
        val resourceBackedSnapshot = adapter.snapshot(
            source(),
            book(
                resources = listOf(
                    MediaResource(
                        mediaType = "ebook",
                        remoteAvailability = RemoteFileAvailability.Available,
                        contentHash = resourceHash,
                        contentHashAlgorithm = "sha-256-v1",
                        nativeResourceId = "cloud-file-id",
                        cloudBookFileId = "cloud-file-id",
                    ),
                ),
                contentHash = resourceHash,
                contentHashAlgorithm = "sha-256-v1",
            ),
            status(),
        )

        assertEquals(1, resourceBackedSnapshot.resources.size)
        assertIs<SourceIdentityEvidence.FileFingerprint>(
            resourceBackedSnapshot.identityEvidence.single(),
        )
    }

    @Test
    fun promotesOnlyUnresolvedSourcesForTheParrotCloudBackend() {
        val unresolvedSource = source().copy(
            key = source().key.copy(
                accountIdentity = SourceAccountIdentity.Unresolved(
                    SourceConnectionId("cloud-connection"),
                ),
            ),
        )
        val portableAccount = SourceAccountIdentity.Portable("parrot-cloud", "cloud-user")

        val promoted = adapter.promoteUnresolvedSourceIdentity(unresolvedSource, portableAccount)

        assertEquals(
            unresolvedSource.copy(
                key = unresolvedSource.key.copy(accountIdentity = portableAccount),
            ),
            promoted,
        )
        assertNull(
            adapter.promoteUnresolvedSourceIdentity(
                unresolvedSource,
                SourceAccountIdentity.Portable("another-backend", "cloud-user"),
            ),
        )
    }

    private fun source() = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId("profile-a"),
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "cloud-user"),
            nativeBookId = NativeBookId("cloud-book-id"),
        ),
        connectionId = SourceConnectionId("cloud-connection"),
        legacyLibraryBookId = LegacyLibraryBookId("sha-256-v1:verified-cloud-hash"),
    )

    private fun book(
        resources: List<MediaResource> = emptyList(),
        contentHash: String? = null,
        contentHashAlgorithm: String? = null,
    ) = ServerBook(
        uuid = "cloud-book-id",
        serverId = "cloud-connection",
        title = "Cloud book",
        description = null,
        coverUrl = null,
        authors = listOf("Author"),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        libraryBookId = "sha-256-v1:verified-cloud-hash",
        contentHash = contentHash,
        contentHashAlgorithm = contentHashAlgorithm,
        mediaResources = resources,
    )

    private fun status() = SourceSnapshotStatus(
        observedAt = Instant.parse("2026-09-25T00:00:00Z"),
        presence = SourcePresence.Present,
        isAuthoritative = false,
    )
}
