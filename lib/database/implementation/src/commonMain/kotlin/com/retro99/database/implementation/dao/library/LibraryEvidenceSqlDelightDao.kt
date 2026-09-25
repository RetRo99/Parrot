package com.retro99.database.implementation.dao.library

import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryEvidenceProvenance
import com.retro99.database.api.library.LibraryEvidenceProvenanceKind
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Library_identity_evidence
import com.retro99.database.implementation.Library_source_resources
import com.retro99.server.api.library.CertifiedIdentityKind
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceResourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal class LibraryEvidenceSqlDelightDao(
    private val profileSession: ProfileDatabaseSession,
    private val databaseProvider: () -> AppDatabase,
) : LibraryEvidenceDatabase {
    override suspend fun recordResource(resource: SourceResourceRef) {
        profileSession.withProfile(resource.book.profileId.value) {
            withContext(Dispatchers.IO) {
                databaseProvider().ensureLibrarySourceResource(resource)
            }
        }
    }

    override suspend fun getResources(book: SourceBookKey): List<SourceResourceRef> =
        profileSession.withProfile(book.profileId.value) {
            withContext(Dispatchers.IO) {
                databaseProvider().libraryEvidenceQueries.getLibrarySourceResourcesForBook(
                    book.profileId.value,
                    LibrarySourceKeyCodec.encode(book),
                ).executeAsList().map { row -> row.toRef() }
            }
        }

    override suspend fun recordEvidence(record: LibraryEvidenceRecord): Boolean {
        val source = record.evidence.sourceResourceOrNull()
        val sourceBook = source?.book
            ?: (record.evidence as SourceIdentityEvidence.BookFingerprint).book
        val destination = (record.evidence as? SourceIdentityEvidence.CompletedTransfer)
            ?.destination
        val profileId = sourceBook.profileId
        require(destination == null || destination.book.profileId == profileId)
        return profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val existing = database.libraryEvidenceQueries.getLibraryIdentityEvidence(
                        profileId.value,
                        record.evidenceId,
                    ).executeAsOneOrNull()
                    if (existing != null) {
                        check(database.toLibraryEvidenceRecord(existing) == record) {
                            "Evidence ID already belongs to a different association"
                        }
                        false
                    } else {
                        val sourceId = source?.let(database::ensureLibrarySourceResource)
                        val destinationId = destination?.let { resource ->
                            database.ensureLibrarySourceResource(resource)
                        }
                        val evidence = record.evidence
                        val fingerprintAlgorithm = when (evidence) {
                            is SourceIdentityEvidence.BookFingerprint -> evidence.algorithm
                            is SourceIdentityEvidence.FileFingerprint -> evidence.algorithm
                            else -> null
                        }
                        val fingerprintHash = when (evidence) {
                            is SourceIdentityEvidence.BookFingerprint -> evidence.hash
                            is SourceIdentityEvidence.FileFingerprint -> evidence.hash
                            else -> null
                        }
                        val fingerprintScope = when (evidence) {
                            is SourceIdentityEvidence.BookFingerprint -> evidence.scope
                            is SourceIdentityEvidence.FileFingerprint -> evidence.scope
                            else -> null
                        }
                        val fingerprintVerification = when (evidence) {
                            is SourceIdentityEvidence.BookFingerprint -> evidence.verification
                            is SourceIdentityEvidence.FileFingerprint -> evidence.verification
                            else -> null
                        }
                        val transfer = evidence as? SourceIdentityEvidence.CompletedTransfer
                        val certified = evidence as? SourceIdentityEvidence.AdapterCertified
                        database.libraryEvidenceQueries.insertLibraryIdentityEvidence(
                            profile_id = profileId.value,
                            evidence_id = record.evidenceId,
                            kind = evidence.storageKind(),
                            source_resource_id = sourceId,
                            source_book_key = (evidence as? SourceIdentityEvidence.BookFingerprint)
                                ?.book
                                ?.let(LibrarySourceKeyCodec::encode),
                            destination_resource_id = destinationId,
                            transfer_id = transfer?.transferId?.value,
                            algorithm = fingerprintAlgorithm,
                            content_hash = fingerprintHash,
                            fingerprint_scope = fingerprintScope?.name,
                            fingerprint_verification = fingerprintVerification?.name,
                            certified_namespace = certified?.namespace,
                            certified_identity = certified?.identity,
                            certified_kind = certified?.kind?.name,
                            provenance_kind = record.provenance.kind.name,
                            provenance_id = record.provenance.referenceId,
                            observed_at = record.observedAt,
                        )
                        true
                    }
                }
            }
        }
    }

    override suspend fun getActiveEvidence(
        profileId: LibraryProfileId,
    ): List<LibraryEvidenceRecord> = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            val database = databaseProvider()
            database.libraryEvidenceQueries.getActiveLibraryIdentityEvidence(profileId.value)
                .executeAsList()
                .map { row -> database.toLibraryEvidenceRecord(row) }
        }
    }

    override suspend fun retireEvidence(profileId: LibraryProfileId, evidenceId: String) {
        require(evidenceId.isNotBlank())
        profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                databaseProvider().libraryEvidenceQueries.retireLibraryIdentityEvidence(
                    profileId.value,
                    evidenceId,
                )
            }
        }
    }
}

internal fun AppDatabase.ensureLibrarySourceResource(resource: SourceResourceRef): Long {
    val profileId = resource.book.profileId.value
    val sourceKey = LibrarySourceKeyCodec.encode(resource.book)
    val revisionPresent = if (resource.revision == null) 0L else 1L
    val revisionValue = resource.revision.orEmpty()
    libraryEvidenceQueries.insertLibrarySourceResource(
        profile_id = profileId,
        source_key = sourceKey,
        native_resource_id = resource.nativeResourceId,
        revision_present = revisionPresent,
        revision_value = revisionValue,
    )
    return libraryEvidenceQueries.getLibrarySourceResource(
        profile_id = profileId,
        source_key = sourceKey,
        native_resource_id = resource.nativeResourceId,
        revision_present = revisionPresent,
        revision_value = revisionValue,
    ).executeAsOne().resource_id
}

private fun Library_source_resources.toRef(): SourceResourceRef = SourceResourceRef(
    book = LibrarySourceKeyCodec.decode(LibraryProfileId(profile_id), source_key),
    nativeResourceId = native_resource_id,
    revision = if (revision_present == 0L) null else revision_value,
)

private fun SourceIdentityEvidence.sourceResourceOrNull(): SourceResourceRef? = when (this) {
    is SourceIdentityEvidence.BookFingerprint -> null
    is SourceIdentityEvidence.FileFingerprint -> resource
    is SourceIdentityEvidence.CompletedTransfer -> source
    is SourceIdentityEvidence.AdapterCertified -> resource
}

private fun SourceIdentityEvidence.storageKind(): String = when (this) {
    is SourceIdentityEvidence.BookFingerprint -> "book_fingerprint"
    is SourceIdentityEvidence.FileFingerprint -> "fingerprint"
    is SourceIdentityEvidence.CompletedTransfer -> "transfer"
    is SourceIdentityEvidence.AdapterCertified -> "certified"
}

internal fun AppDatabase.toLibraryEvidenceRecord(
    row: Library_identity_evidence,
): LibraryEvidenceRecord {
    val source = row.source_resource_id?.let { sourceResourceId ->
        checkNotNull(libraryEvidenceQueries.getLibrarySourceResourceById(
            row.profile_id,
            sourceResourceId,
        ).executeAsOneOrNull()) { "Evidence source resource is missing" }.toRef()
    }
    val profileId = LibraryProfileId(row.profile_id)
    val evidence = when (row.kind) {
        "book_fingerprint" -> SourceIdentityEvidence.BookFingerprint(
            book = LibrarySourceKeyCodec.decode(profileId, checkNotNull(row.source_book_key)),
            algorithm = row.algorithm,
            hash = checkNotNull(row.content_hash),
            scope = FingerprintScope.valueOf(checkNotNull(row.fingerprint_scope)),
            verification = FingerprintVerification.valueOf(
                checkNotNull(row.fingerprint_verification),
            ),
        )
        "fingerprint" -> SourceIdentityEvidence.FileFingerprint(
            resource = checkNotNull(source),
            algorithm = row.algorithm,
            hash = checkNotNull(row.content_hash),
            scope = FingerprintScope.valueOf(checkNotNull(row.fingerprint_scope)),
            verification = FingerprintVerification.valueOf(
                checkNotNull(row.fingerprint_verification),
            ),
        )
        "transfer" -> {
            val destination = checkNotNull(libraryEvidenceQueries.getLibrarySourceResourceById(
                row.profile_id,
                checkNotNull(row.destination_resource_id),
            ).executeAsOneOrNull()) { "Evidence destination resource is missing" }.toRef()
            SourceIdentityEvidence.CompletedTransfer(
                transferId = LibraryTransferId(checkNotNull(row.transfer_id)),
                source = checkNotNull(source),
                destination = destination,
            )
        }
        "certified" -> SourceIdentityEvidence.AdapterCertified(
            resource = checkNotNull(source),
            namespace = checkNotNull(row.certified_namespace),
            identity = checkNotNull(row.certified_identity),
            kind = CertifiedIdentityKind.valueOf(checkNotNull(row.certified_kind)),
        )
        else -> error("Unknown identity evidence kind: ${row.kind}")
    }
    return LibraryEvidenceRecord(
        evidenceId = row.evidence_id,
        evidence = evidence,
        provenance = LibraryEvidenceProvenance(
            kind = LibraryEvidenceProvenanceKind.valueOf(row.provenance_kind),
            referenceId = row.provenance_id,
        ),
        observedAt = row.observed_at,
    )
}
