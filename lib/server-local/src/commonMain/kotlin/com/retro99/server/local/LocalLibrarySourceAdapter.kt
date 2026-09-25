package com.retro99.server.local

import com.retro99.server.api.MediaResource
import com.retro99.server.api.ServerBook
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.ServerBookLibrarySourceAdapter
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceSnapshotStatus
import org.koin.core.annotation.Single

@Single(binds = [LibrarySourceAdapter::class])
class LocalLibrarySourceAdapter : ServerBookLibrarySourceAdapter() {
    override val adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID)

    override fun includeDeviceStorage(): Boolean = true

    override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ): SourceBookSnapshot = super.snapshot(
        source = source.withPortableLocalContentIdentity(book),
        book = book,
        status = status,
    )

    override fun fingerprint(
        resource: com.retro99.server.api.library.SourceMediaResource,
        serverResource: MediaResource,
    ): SourceIdentityEvidence.FileFingerprint? {
        val algorithm = serverResource.contentHashAlgorithm
        val hash = LocalContentIdentity.canonicalHash(serverResource.contentHash)
        return if (algorithm == LocalContentIdentity.HASH_ALGORITHM && hash != null) {
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

    private fun SourceBookRef.withPortableLocalContentIdentity(book: ServerBook): SourceBookRef {
        val contentHash = book.mediaResources
            .asSequence()
            .mapNotNull { resource ->
                if (resource.contentHashAlgorithm != LocalContentIdentity.HASH_ALGORITHM) {
                    return@mapNotNull null
                }
                LocalContentIdentity.canonicalHash(resource.contentHash)
            }
            .firstOrNull()
            ?: return this
        val sourceKey = SourceBookKey(
            profileId = key.profileId,
            adapterId = adapterId,
            accountIdentity = SourceAccountIdentity.Portable(
                backendId = LocalContentIdentity.BACKEND_ID,
                accountId = LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(contentHash),
        )
        return copy(
            key = sourceKey,
            legacyLibraryBookId = LegacyLibraryBookId(sourceKey.nativeBookId.value),
        )
    }
}
