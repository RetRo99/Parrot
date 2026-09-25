package com.retro99.server.api.library

data class LibraryTransferId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Resource identity is native to the source book, not just its media type. */
data class SourceResourceRef(
    val book: SourceBookKey,
    val nativeResourceId: String,
    val revision: String? = null,
) {
    init { require(nativeResourceId.isNotBlank()) }
}

/**
 * Adapter-reported evidence, not a grouping decision. An empty evidence list is
 * valid. Unknown algorithms/scopes and unverified fingerprints cannot prove
 * equality; the library policy must validate matching semantics before joining.
 */
sealed interface SourceIdentityEvidence {
    /**
     * A whole asset hash certified for the source book as a whole. This is useful when an
     * adapter exposes a verified content hash but has no native resource reference to attach
     * it to. It does not claim that a file resource is currently available or locatable.
     */
    data class BookFingerprint(
        val book: SourceBookKey,
        val algorithm: String?,
        val hash: String,
        val scope: FingerprintScope,
        val verification: FingerprintVerification,
    ) : SourceIdentityEvidence {
        init { require(hash.isNotBlank()) }
    }

    data class FileFingerprint(
        val resource: SourceResourceRef,
        val algorithm: String?,
        val hash: String,
        val scope: FingerprintScope,
        val verification: FingerprintVerification,
    ) : SourceIdentityEvidence {
        init { require(hash.isNotBlank()) }
    }

    /** Both concrete endpoints must be known; a pending upload is not evidence. */
    data class CompletedTransfer(
        val transferId: LibraryTransferId,
        val source: SourceResourceRef,
        val destination: SourceResourceRef,
    ) : SourceIdentityEvidence {
        init {
            require(source.book.profileId == destination.book.profileId) {
                "Transfer evidence cannot cross local profiles"
            }
        }
    }

    /**
     * An adapter-owned namespace and matching contract, not an ISBN/title match.
     * Edition evidence does not establish byte or progress compatibility.
     */
    data class AdapterCertified(
        val resource: SourceResourceRef,
        val namespace: String,
        val identity: String,
        val kind: CertifiedIdentityKind,
    ) : SourceIdentityEvidence {
        init {
            require(namespace.isNotBlank())
            require(identity.isNotBlank())
        }
    }
}

enum class FingerprintScope { WholeFile, Manifest, Unknown }

enum class FingerprintVerification { Verified, Unverified }

enum class CertifiedIdentityKind { Edition, Asset }
