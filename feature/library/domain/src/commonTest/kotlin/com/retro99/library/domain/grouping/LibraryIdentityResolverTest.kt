package com.retro99.library.domain.grouping

import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceResourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryIdentityResolverTest {
    @Test
    fun verifiedCompatibleFingerprintsJoinDifferentLegacySources() {
        // Given
        val local = source("local", "import-id")
        val cloud = source("cloud", "cloud-id")
        val assignments = listOf(assignment(local, "group-local"), assignment(cloud, "group-cloud"))
        val evidence = listOf(
            fingerprint("local-file", local, algorithm = "sha-256-v1", hash = "same-bytes"),
            fingerprint("cloud-file", cloud, algorithm = "sha-256-v1", hash = "same-bytes"),
        )

        // When
        val plan = LibraryIdentityResolver.resolve(assignments, emptySet(), evidence)

        // Then
        assertEquals(1, plan.automaticJoins.size)
        assertEquals(LibraryGroupId("group-cloud"), plan.automaticJoins.single().survivorGroupId)
        assertEquals(setOf("local-file", "cloud-file"), plan.automaticJoins.single().evidenceIds)
        assertEquals(setOf(local, cloud), plan.automaticJoins.single().sourceKeys)
    }

    @Test
    fun bookFingerprintJoinsOnlyCompatibleVerifiedWholeFileEvidence() {
        // Given
        val local = source("local", "import-id")
        val cloud = source("cloud", "cloud-id")
        val assignments = listOf(assignment(local, "group-local"), assignment(cloud, "group-cloud"))
        val evidence = listOf(
            fingerprint("local-file", local, "sha-256-v1", "same-bytes"),
            bookFingerprint("cloud-book", cloud, "sha-256-v1", "same-bytes"),
        )

        // When
        val plan = LibraryIdentityResolver.resolve(assignments, emptySet(), evidence)

        // Then
        assertEquals(1, plan.automaticJoins.size)
        assertEquals(
            setOf("cloud-book", "local-file"),
            plan.automaticJoins.single().evidenceIds,
        )

        val incompatibleEvidence = listOf(
            fingerprint("local-file", local, "sha-256-v1", "same-bytes"),
            bookFingerprint(
                "cloud-manifest",
                cloud,
                algorithm = "sha-256-v1",
                hash = "same-bytes",
                scope = FingerprintScope.Manifest,
            ),
        )
        val incompatiblePlan = LibraryIdentityResolver.resolve(
            assignments,
            emptySet(),
            incompatibleEvidence,
        )
        assertTrue(incompatiblePlan.automaticJoins.isEmpty())
    }

    @Test
    fun metadataOnlyAndUnknownFingerprintSemanticsDoNotJoin() {
        // Given
        val first = source("local", "book-a")
        val second = source("cloud", "book-b")
        val assignments = listOf(assignment(first, "group-a"), assignment(second, "group-b"))
        val evidence = listOf(
            fingerprint("unknown-algorithm", first, algorithm = null, hash = "hash"),
            fingerprint("other-source", second, algorithm = "sha-256-v1", hash = "hash"),
        )

        // When
        val plan = LibraryIdentityResolver.resolve(assignments, emptySet(), evidence)

        // Then
        assertTrue(plan.automaticJoins.isEmpty())
        assertTrue(plan.blockedJoins.isEmpty())
    }

    @Test
    fun differentFingerprintAlgorithmsAndScopesDoNotJoin() {
        // Given
        val first = source("local", "book-a")
        val second = source("cloud", "book-b")
        val assignments = listOf(assignment(first, "group-a"), assignment(second, "group-b"))
        val evidence = listOf(
            fingerprint("whole-file", first, "sha-256-v1", "digest", FingerprintScope.WholeFile),
            fingerprint("manifest", second, "sha-256-v1", "digest", FingerprintScope.Manifest),
        )

        // When
        val plan = LibraryIdentityResolver.resolve(assignments, emptySet(), evidence)

        // Then
        assertTrue(plan.automaticJoins.isEmpty())
    }

    @Test
    fun completedTransferLinksJoinEvenWhenResourcesHaveDifferentIds() {
        // Given
        val local = source("local", "import-id")
        val cloud = source("cloud", "cloud-id")
        val evidence = LibraryIdentityEvidence(
            "completed-upload",
            SourceIdentityEvidence.CompletedTransfer(
                transferId = LibraryTransferId("transfer-1"),
                source = SourceResourceRef(local, "local-epub"),
                destination = SourceResourceRef(cloud, "cloud-file-uuid"),
            ),
        )

        // When
        val plan = LibraryIdentityResolver.resolve(
            listOf(assignment(local, "group-local"), assignment(cloud, "group-cloud")),
            emptySet(),
            listOf(evidence),
        )

        // Then
        assertEquals(setOf("completed-upload"), plan.automaticJoins.single().evidenceIds)
    }

    @Test
    fun separationBlocksTransitiveEvidenceAcrossProspectiveMemberSets() {
        // Given
        val first = source("a", "book-a")
        val second = source("b", "book-b")
        val third = source("c", "book-c")
        val separation = LibrarySourceSeparation(first, third)
        val assignments = listOf(
            assignment(first, "group-a"),
            assignment(second, "group-b"),
            assignment(third, "group-c"),
        )
        val evidence = listOf(
            fingerprint("a-b", first, "sha-256-v1", "hash-ab"),
            fingerprint("b-again", second, "sha-256-v1", "hash-ab"),
            fingerprint("b-c", second, "sha-256-v1", "hash-bc"),
            fingerprint("c-again", third, "sha-256-v1", "hash-bc"),
        )

        // When
        val plan = LibraryIdentityResolver.resolve(assignments, setOf(separation), evidence)

        // Then
        assertEquals(1, plan.automaticJoins.size)
        assertEquals(setOf(first, second), plan.automaticJoins.single().sourceKeys)
        assertEquals(1, plan.blockedJoins.size)
        assertEquals(separation, plan.blockedJoins.single().separation)
    }

    @Test
    fun inputOrderDoesNotChangeTheJoinPlanAndProfilesStaySeparate() {
        // Given
        val local = source("local", "book-a")
        val cloud = source("cloud", "book-b")
        val otherProfile = source("cloud", "book-b", profileId = "profile-b")
        val assignments = listOf(
            assignment(local, "group-z"),
            assignment(cloud, "group-a"),
            assignment(otherProfile, "group-a"),
        )
        val evidence = listOf(
            fingerprint("local-fingerprint", local, "sha-256-v1", "matching"),
            fingerprint("cloud-fingerprint", cloud, "sha-256-v1", "matching"),
            fingerprint("other-profile", otherProfile, "sha-256-v1", "matching"),
        )

        // When
        val firstPlan = LibraryIdentityResolver.resolve(assignments, emptySet(), evidence)
        val replayPlan = LibraryIdentityResolver.resolve(
            assignments.reversed(),
            emptySet(),
            evidence.reversed(),
        )

        // Then
        assertEquals(firstPlan, replayPlan)
        assertEquals(1, firstPlan.automaticJoins.size)
        assertEquals(LibraryProfileId("profile-a"), firstPlan.automaticJoins.single().profileId)
    }

    private fun assignment(source: SourceBookKey, groupId: String) =
        LibraryMembershipAssignment(source, LibraryGroupId(groupId))

    private fun fingerprint(
        evidenceId: String,
        source: SourceBookKey,
        algorithm: String?,
        hash: String,
        scope: FingerprintScope = FingerprintScope.WholeFile,
        verification: FingerprintVerification = FingerprintVerification.Verified,
    ) = LibraryIdentityEvidence(
        evidenceId,
        SourceIdentityEvidence.FileFingerprint(
            resource = SourceResourceRef(source, "${evidenceId}-resource"),
            algorithm = algorithm,
            hash = hash,
            scope = scope,
            verification = verification,
        ),
    )

    private fun bookFingerprint(
        evidenceId: String,
        source: SourceBookKey,
        algorithm: String?,
        hash: String,
        scope: FingerprintScope = FingerprintScope.WholeFile,
        verification: FingerprintVerification = FingerprintVerification.Verified,
    ) = LibraryIdentityEvidence(
        evidenceId,
        SourceIdentityEvidence.BookFingerprint(
            book = source,
            algorithm = algorithm,
            hash = hash,
            scope = scope,
            verification = verification,
        ),
    )

    private fun source(
        adapterId: String,
        nativeBookId: String,
        profileId: String = "profile-a",
    ) = SourceBookKey(
        profileId = LibraryProfileId(profileId),
        adapterId = LibraryAdapterId(adapterId),
        accountIdentity = SourceAccountIdentity.Portable("backend", "account"),
        nativeBookId = NativeBookId(nativeBookId),
    )
}
