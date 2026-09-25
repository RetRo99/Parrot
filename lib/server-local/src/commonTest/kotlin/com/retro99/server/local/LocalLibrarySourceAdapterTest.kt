package com.retro99.server.local

import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class LocalLibrarySourceAdapterTest {
    private val adapter = LocalLibrarySourceAdapter()

    @Test
    fun verifiedWholeFileHashBecomesScopedEvidenceAndKeepsDeviceReference() {
        val resource = MediaResource(
            mediaType = "ebook",
            localPath = "/device/import/book.epub",
            remoteAvailability = RemoteFileAvailability.None,
            contentHash = VALID_HASH,
            contentHashAlgorithm = LocalContentIdentity.HASH_ALGORITHM,
            nativeResourceId = "imported-book-1",
            format = "epub",
        )

        val source = source()
        val snapshot = adapter.snapshot(source, book(resource), status())

        assertEquals(1, snapshot.resources.size)
        assertEquals(
            SourceResourceAvailability.DevicePresent,
            snapshot.resources.single().availability,
        )
        assertEquals(
            "/device/import/book.epub",
            snapshot.resources.single().localStorageReference?.value,
        )
        assertEquals("imported-book-1", snapshot.resources.single().reference.nativeResourceId)
        assertEquals(
            "${LocalContentIdentity.HASH_ALGORITHM}:$VALID_HASH",
            snapshot.source.key.nativeBookId.value,
        )
        assertIs<SourceAccountIdentity.Portable>(snapshot.source.key.accountIdentity)
        assertEquals(snapshot.source.key, snapshot.resources.single().reference.book)
        val fingerprint = assertIs<SourceIdentityEvidence.FileFingerprint>(
            snapshot.identityEvidence.single(),
        )
        assertEquals(LocalContentIdentity.HASH_ALGORITHM, fingerprint.algorithm)
        assertEquals(VALID_HASH, fingerprint.hash)
        assertEquals(FingerprintScope.WholeFile, fingerprint.scope)
        assertEquals(FingerprintVerification.Verified, fingerprint.verification)
    }

    @Test
    fun unknownFingerprintAlgorithmDoesNotProduceAutomaticJoinEvidence() {
        val resource = MediaResource(
            mediaType = "ebook",
            localPath = "/device/import/book.epub",
            contentHash = "hash",
            contentHashAlgorithm = "unknown-v2",
            nativeResourceId = "imported-book-1",
        )

        val snapshot = adapter.snapshot(source(), book(resource), status())

        assertEquals(emptyList(), snapshot.identityEvidence)
        assertNull(snapshot.resources.single().remoteResourceReference)
        assertEquals(source().key, snapshot.source.key)
    }

    @Test
    fun sameVerifiedEpubHasTheSamePortableIdentityAcrossConnectionIds() {
        val resource = MediaResource(
            mediaType = "ebook",
            localPath = "/device/import/book.epub",
            contentHash = VALID_HASH,
            contentHashAlgorithm = LocalContentIdentity.HASH_ALGORITHM,
            nativeResourceId = "imported-book-1",
            format = "epub",
        )

        val first = adapter.snapshot(source("local-connection-a"), book(resource), status())
        val second = adapter.snapshot(source("local-connection-b"), book(resource), status())

        assertEquals(first.source.key, second.source.key)
        assertEquals("local-connection-a", first.source.connectionId?.value)
        assertEquals("local-connection-b", second.source.connectionId?.value)
        assertEquals("imported-book-1", first.resources.single().reference.nativeResourceId)
        assertEquals("imported-book-1", second.resources.single().reference.nativeResourceId)
    }

    @Test
    fun missingMalformedAndUnsupportedHashesKeepLocalIdentityUnresolved() {
        val invalidFingerprints = listOf(
            "unknown-v2" to VALID_HASH,
            LocalContentIdentity.HASH_ALGORITHM to "short-hash",
            null to VALID_HASH,
            LocalContentIdentity.HASH_ALGORITHM to null,
        )

        invalidFingerprints.forEach { (algorithm, hash) ->
            val source = source()
            val resource = MediaResource(
                mediaType = "ebook",
                localPath = "/device/import/book.epub",
                contentHash = hash,
                contentHashAlgorithm = algorithm,
                nativeResourceId = "imported-book-1",
                format = "epub",
            )

            val snapshot = adapter.snapshot(source, book(resource), status())

            assertEquals(source.key, snapshot.source.key)
            assertEquals(emptyList(), snapshot.identityEvidence)
            assertEquals("imported-book-1", snapshot.resources.single().reference.nativeResourceId)
        }
    }

    private fun source(connectionId: String = "local-connection"): SourceBookRef {
        val connection = SourceConnectionId(connectionId)
        return SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId("profile"),
                adapterId = LibraryAdapterId("local"),
                accountIdentity = SourceAccountIdentity.Unresolved(connection),
                nativeBookId = NativeBookId("book-1"),
            ),
            connectionId = connection,
        )
    }

    private fun book(resource: MediaResource) = ServerBook(
        uuid = "book-1",
        serverId = "local-connection",
        title = "Imported book",
        description = null,
        coverUrl = null,
        authors = listOf("Author"),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        mediaResources = listOf(resource),
    )

    private fun status() = SourceSnapshotStatus(
        observedAt = Instant.parse("2026-09-24T00:00:00Z"),
        presence = SourcePresence.Present,
        isAuthoritative = true,
    )

    private companion object {
        const val VALID_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
