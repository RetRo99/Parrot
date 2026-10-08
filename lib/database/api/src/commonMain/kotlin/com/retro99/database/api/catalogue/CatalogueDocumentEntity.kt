package com.retro99.database.api.catalogue

/** A saved copy of one catalogue page, keyed by source, access generation and request URL. */
class CatalogueDocumentEntity(
    val sourceId: String,
    val accessGeneration: Long,
    val requestUrl: String,
    val contentType: String?,
    val eTag: String?,
    val lastModified: String?,
    val storedAt: Long,
    val payload: ByteArray,
    val sizeBytes: Long = payload.size.toLong(),
    /** Where the page was served from after redirects; null when that is [requestUrl]. */
    val effectiveUrl: String? = null,
) {
    override fun toString(): String = "CatalogueDocumentEntity(redacted, $sizeBytes bytes)"
}

data class CatalogueDocumentKey(
    val sourceId: String,
    val accessGeneration: Long,
    val requestUrl: String,
    val sizeBytes: Long,
) {
    override fun toString(): String = "CatalogueDocumentKey(redacted)"
}
