package com.retro99.server.api.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SourceBookRefTest {
    @Test
    fun sameNativeBookIdDoesNotCollideAcrossSourceScopes() {
        val key = key()
        val memberships = setOf(
            key,
            key.copy(profileId = LibraryProfileId("other-profile")),
            key.copy(adapterId = LibraryAdapterId("another-unregistered-adapter")),
            key.copy(accountIdentity = SourceAccountIdentity.Portable("backend", "other-account")),
            key.copy(accountIdentity = SourceAccountIdentity.Portable("other-backend", "account")),
        )

        assertEquals(5, memberships.size)
    }

    @Test
    fun reconnectingPortableMemberRetainsMembershipKeyAndLegacyAlias() {
        val ref = SourceBookRef(
            key = key(),
            connectionId = SourceConnectionId("connection-one"),
            legacyLibraryBookId = LegacyLibraryBookId("sha-256-v1:hash"),
        )
        val memberships = mapOf(ref.key to "persisted-membership")
        val disconnected = ref.copy(connectionId = null)
        val reconnected = disconnected.copy(connectionId = SourceConnectionId("connection-two"))

        assertEquals("persisted-membership", memberships[disconnected.key])
        assertEquals("persisted-membership", memberships[reconnected.key])
        assertEquals(ref.legacyLibraryBookId, reconnected.legacyLibraryBookId)
    }

    @Test
    fun unresolvedConnectionsRemainDistinctUntilExplicitResolution() {
        val first = SourceConnectionId("first")
        val second = SourceConnectionId("second")
        val firstKey = key().copy(accountIdentity = SourceAccountIdentity.Unresolved(first))
        val secondKey = key().copy(accountIdentity = SourceAccountIdentity.Unresolved(second))

        assertNotEquals(firstKey, secondKey)
        assertNotEquals(firstKey, key())
        assertFailsWith<IllegalArgumentException> { SourceBookRef(firstKey, second) }
        assertFailsWith<IllegalArgumentException> { SourceBookRef(firstKey, null) }
        assertEquals(firstKey, SourceBookRef(firstKey, first).key)
    }

    @Test
    fun completedTransferCannotAssociateResourcesFromDifferentProfiles() {
        val source = SourceResourceRef(key(), "ebook-file")
        val destination = SourceResourceRef(
            key().copy(profileId = LibraryProfileId("other-profile")),
            "remote-file",
        )

        assertFailsWith<IllegalArgumentException> {
            SourceIdentityEvidence.CompletedTransfer(LibraryTransferId("transfer"), source, destination)
        }
    }

    @Test
    fun fileFingerprintAndCertifiedEditionRemainDifferentEvidenceKinds() {
        val resource = SourceResourceRef(key(), "resource")
        val fingerprint: SourceIdentityEvidence = SourceIdentityEvidence.FileFingerprint(
            resource = resource,
            algorithm = "sha-256-v1",
            hash = "hash",
            scope = FingerprintScope.WholeFile,
            verification = FingerprintVerification.Verified,
        )
        val edition: SourceIdentityEvidence = SourceIdentityEvidence.AdapterCertified(
            resource = resource,
            namespace = "fake-editions",
            identity = "edition-1",
            kind = CertifiedIdentityKind.Edition,
        )

        assertNotEquals(fingerprint, edition)
        assertEquals(2, setOf(fingerprint, edition).size)
    }

    private fun key() = SourceBookKey(
        profileId = LibraryProfileId("profile"),
        adapterId = LibraryAdapterId("arbitrary-test-adapter"),
        accountIdentity = SourceAccountIdentity.Portable("backend", "account"),
        nativeBookId = NativeBookId("native-book"),
    )
}
