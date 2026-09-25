package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryEvidenceProvenance
import com.retro99.database.api.library.LibraryEvidenceProvenanceKind
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.implementation.dao.library.LibraryEvidenceSqlDelightDao
import com.retro99.server.api.library.CertifiedIdentityKind
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceResourceRef
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryEvidenceSqlDelightDaoTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase
    private lateinit var session: ActiveProfileSession
    private lateinit var classUnderTest: LibraryEvidenceDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        session = ActiveProfileSession("profile-a")
        classUnderTest = LibraryEvidenceSqlDelightDao(session) { database }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun resourceIdentityPreservesScopeAndDistinguishesMissingFromEmptyRevision() = runBlocking {
        // Given
        val first = resource("profile-a", "account-a", "book", "file", null)
        val emptyRevision = first.copy(revision = "")
        val otherAccount = resource("profile-a", "account-b", "book", "file", null)
        val otherProfile = resource("profile-b", "account-a", "book", "file", null)

        // When
        classUnderTest.recordResource(first)
        classUnderTest.recordResource(first)
        classUnderTest.recordResource(emptyRevision)
        classUnderTest.recordResource(otherAccount)

        // Then
        assertEquals(
            setOf(first, emptyRevision),
            classUnderTest.getResources(first.book).toSet(),
        )
        assertEquals(listOf(otherAccount), classUnderTest.getResources(otherAccount.book))
        assertFailsWith<IllegalStateException> { classUnderTest.recordResource(otherProfile) }
        Unit
    }

    @Test
    fun evidenceKindsRoundTripAndRetiredEvidenceCannotDriveJoins() = runBlocking {
        // Given
        val local = resource("profile-a", "account-a", "book-local", "epub", "revision-1")
        val cloud = resource("profile-a", "account-a", "book-cloud", "file-uuid", null)
        val fingerprint = evidenceRecord(
            "fingerprint-id",
            SourceIdentityEvidence.FileFingerprint(
                resource = local,
                algorithm = null,
                hash = "hash-value",
                scope = FingerprintScope.Unknown,
                verification = FingerprintVerification.Unverified,
            ),
            LibraryEvidenceProvenanceKind.Adapter,
        )
        val transfer = evidenceRecord(
            "transfer-id",
            SourceIdentityEvidence.CompletedTransfer(
                transferId = LibraryTransferId("completed-transfer"),
                source = local,
                destination = cloud,
            ),
            LibraryEvidenceProvenanceKind.Transfer,
        )
        val certified = evidenceRecord(
            "certified-id",
            SourceIdentityEvidence.AdapterCertified(
                resource = cloud,
                namespace = "edition-namespace",
                identity = "edition-identity",
                kind = CertifiedIdentityKind.Edition,
            ),
            LibraryEvidenceProvenanceKind.Adapter,
        )

        // When
        assertEquals(true, classUnderTest.recordEvidence(fingerprint))
        assertEquals(true, classUnderTest.recordEvidence(transfer))
        assertEquals(true, classUnderTest.recordEvidence(certified))
        assertEquals(false, classUnderTest.recordEvidence(transfer))
        assertEquals(
            listOf(certified, fingerprint, transfer),
            classUnderTest.getActiveEvidence(local.book.profileId),
        )
        classUnderTest.retireEvidence(local.book.profileId, "fingerprint-id")
        assertEquals(false, classUnderTest.recordEvidence(fingerprint))

        // Then
        assertEquals(
            listOf(certified, transfer),
            classUnderTest.getActiveEvidence(local.book.profileId),
        )
        assertEquals(setOf(local), classUnderTest.getResources(local.book).toSet())
        assertEquals(setOf(cloud), classUnderTest.getResources(cloud.book).toSet())
    }

    @Test
    fun evidenceIdConflictAndCrossProfileEndpointsAreRejected() = runBlocking {
        // Given
        val local = resource("profile-a", "account-a", "book", "file", null)
        val otherProfile = resource("profile-b", "account-a", "book", "file", null)
        val first = evidenceRecord(
            "shared-id",
            SourceIdentityEvidence.FileFingerprint(
                resource = local,
                algorithm = "sha-256-v1",
                hash = "first-hash",
                scope = FingerprintScope.WholeFile,
                verification = FingerprintVerification.Verified,
            ),
            LibraryEvidenceProvenanceKind.Migration,
        )
        classUnderTest.recordEvidence(first)

        // When
        val conflicting = first.copy(
            evidence = (first.evidence as SourceIdentityEvidence.FileFingerprint)
                .copy(hash = "second-hash"),
        )

        // Then
        assertFailsWith<IllegalStateException> { classUnderTest.recordEvidence(conflicting) }
        assertFailsWith<IllegalArgumentException> {
            SourceIdentityEvidence.CompletedTransfer(
                LibraryTransferId("cross-profile"),
                local,
                otherProfile,
            )
        }
        assertEquals(listOf(first), classUnderTest.getActiveEvidence(local.book.profileId))
    }

    @Test
    fun bookFingerprintRoundTripsWithoutCreatingAResourceAndCanBeRetired() = runBlocking {
        // Given
        val book = sourceBook("profile-a", "account-a", "cloud-book")
        val fingerprint = evidenceRecord(
            "cloud-book-hash",
            SourceIdentityEvidence.BookFingerprint(
                book = book,
                algorithm = "sha-256-v1",
                hash = "verified-whole-file-hash",
                scope = FingerprintScope.WholeFile,
                verification = FingerprintVerification.Verified,
            ),
            LibraryEvidenceProvenanceKind.Adapter,
        )

        // When
        assertEquals(true, classUnderTest.recordEvidence(fingerprint))

        // Then
        assertEquals(emptyList(), classUnderTest.getResources(book))
        assertEquals(listOf(fingerprint), classUnderTest.getActiveEvidence(book.profileId))

        classUnderTest.retireEvidence(book.profileId, fingerprint.evidenceId)
        assertEquals(emptyList(), classUnderTest.getActiveEvidence(book.profileId))
    }

    private fun resource(
        profileId: String,
        accountId: String,
        nativeBookId: String,
        nativeResourceId: String,
        revision: String?,
    ): SourceResourceRef = SourceResourceRef(
        book = sourceBook(profileId, accountId, nativeBookId),
        nativeResourceId = nativeResourceId,
        revision = revision,
    )

    private fun sourceBook(
        profileId: String,
        accountId: String,
        nativeBookId: String,
    ) = SourceBookKey(
        profileId = LibraryProfileId(profileId),
        adapterId = LibraryAdapterId("test-adapter"),
        accountIdentity = SourceAccountIdentity.Portable("backend", accountId),
        nativeBookId = NativeBookId(nativeBookId),
    )

    private fun evidenceRecord(
        evidenceId: String,
        evidence: SourceIdentityEvidence,
        provenanceKind: LibraryEvidenceProvenanceKind,
    ): LibraryEvidenceRecord = LibraryEvidenceRecord(
        evidenceId = evidenceId,
        evidence = evidence,
        provenance = LibraryEvidenceProvenance(provenanceKind, "source-event"),
        observedAt = "2026-09-24T00:00:00Z",
    )

    private class ActiveProfileSession(
        private val activeProfileId: String,
    ) : ProfileDatabaseSession {
        override suspend fun <T> withProfile(
            localProfileId: String,
            operation: suspend () -> T,
        ): T {
            check(localProfileId == activeProfileId)
            return operation()
        }
    }
}
