package com.retro99.server.parrotcloud

import com.retro99.server.api.MediaResource
import com.retro99.server.api.ServerBook
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.LibrarySourceIdentityPromotionAdapter
import com.retro99.server.api.library.ServerBookLibrarySourceAdapter
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourceSnapshotStatus
import org.koin.core.annotation.Single

@Single(binds = [LibrarySourceAdapter::class])
class ParrotCloudLibrarySourceAdapter : ServerBookLibrarySourceAdapter(),
    LibrarySourceIdentityPromotionAdapter {
    override val adapterId = LibraryAdapterId("parrot-cloud")

    override fun promoteUnresolvedSourceIdentity(
        unresolvedSource: SourceBookRef,
        portableAccountIdentity: SourceAccountIdentity.Portable,
    ): SourceBookRef? {
        val unresolvedIdentity = unresolvedSource.key.accountIdentity
            as? SourceAccountIdentity.Unresolved ?: return null
        if (
            unresolvedSource.key.adapterId != adapterId ||
            unresolvedIdentity.connectionId != unresolvedSource.connectionId ||
            portableAccountIdentity.backendId != PARROT_CLOUD_BACKEND_ID
        ) {
            return null
        }
        return unresolvedSource.copy(
            key = unresolvedSource.key.copy(accountIdentity = portableAccountIdentity),
        )
    }

    override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ): SourceBookSnapshot {
        val snapshot = super.snapshot(source, book, status)
        val algorithm = book.contentHashAlgorithm
        val hash = book.contentHash
        if (algorithm != CONTENT_HASH_ALGORITHM || hash.isNullOrBlank()) return snapshot

        val alreadyRepresentedByResource = snapshot.identityEvidence.any { evidence ->
            evidence is SourceIdentityEvidence.FileFingerprint &&
                evidence.algorithm == algorithm &&
                evidence.hash == hash &&
                evidence.scope == FingerprintScope.WholeFile &&
                evidence.verification == FingerprintVerification.Verified
        }
        return if (alreadyRepresentedByResource) {
            snapshot
        } else {
            snapshot.copy(
                identityEvidence = snapshot.identityEvidence +
                    SourceIdentityEvidence.BookFingerprint(
                        book = source.key,
                        algorithm = algorithm,
                        hash = hash,
                        scope = FingerprintScope.WholeFile,
                        verification = FingerprintVerification.Verified,
                    ),
            )
        }
    }

    override fun fingerprint(
        resource: SourceMediaResource,
        serverResource: MediaResource,
    ): SourceIdentityEvidence.FileFingerprint? {
        val algorithm = serverResource.contentHashAlgorithm
        val hash = serverResource.contentHash
        return if (algorithm == CONTENT_HASH_ALGORITHM && !hash.isNullOrBlank()) {
            SourceIdentityEvidence.FileFingerprint(
                resource = resource.reference,
                algorithm = algorithm,
                hash = hash,
                scope = FingerprintScope.WholeFile,
                verification = FingerprintVerification.Verified,
            )
        } else {
            null
        }
    }

    private companion object {
        const val CONTENT_HASH_ALGORITHM = "sha-256-v1"
        const val PARROT_CLOUD_BACKEND_ID = "parrot-cloud"
    }
}
