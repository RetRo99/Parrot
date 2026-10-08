package com.retro99.opds.api.model

/**
 * Stable identities (plan §3.2, §5.4): never derived from a title, an array
 * position, an ISBN alone, or a download URL. OPDS1 uses `atom:id`; OPDS2
 * prefers a publication `self` resource identity.
 */
data class OpdsIdentity(
    val raw: String,
    val kind: Kind,
    /** Present only when [kind] is not [Kind.NOMINAL] (plan §3.2 identity fallback). */
    val note: String? = null,
) {
    enum class Kind {
        /** A feed-declared identity (`urn:uuid:…`, `urn:gutenberg:…`, OPDS2 self/../metadata.identifier). */
        NOMINAL,

        /** Derivable within one document; cannot promise cross-feed deduplication (plan §3.2). */
        DOCUMENT_SCOPED_FALLBACK,

        /** Provider-scoped deterministic fallback for publication documents with a base identity. */
        PROVIDER_SCOPED_FALLBACK,
    }

    companion object {
        /** Deterministic document-scoped fallback: "title-less" stable key inside one document. */
        fun documentScoped(raw: String, note: String? = null): OpdsIdentity =
            OpdsIdentity("doc:$raw", Kind.DOCUMENT_SCOPED_FALLBACK, note)
    }
}
