package com.retro99.catalogue.domain

/**
 * A request to download one file of one catalogue book.
 *
 * [listingUrl] is the catalogue page that lists the book. It is kept, on this device only,
 * so the file's link can be looked up again right before downloading; the file's own link
 * is never stored. The text fields are a snapshot for showing the download while the
 * catalogue is unreachable; they are cut to the lengths in [CatalogueAcquisitionLimits].
 */
data class CatalogueAcquisitionRequest(
    val sourceId: String,
    val publicationKey: String,
    val representationKey: String,
    val detailIdentity: String?,
    val listingUrl: String,
    val title: String,
    val author: String?,
    val coverReference: String?,
    val catalogueName: String,
    /** The size the catalogue lists for the file, when it lists one. */
    val expectedSizeBytes: Long? = null,
) {
    override fun toString() = "CatalogueAcquisitionRequest(redacted)"
}

/** One download as stored. Times are epoch milliseconds. */
data class CatalogueAcquisition(
    val requestId: String,
    val sourceId: String,
    val publicationKey: String,
    val representationKey: String,
    val detailIdentity: String?,
    val title: String,
    val author: String?,
    val coverReference: String?,
    val catalogueName: String,
    val state: AcquisitionState,
    /** Lower starts first. A retried or restarted download goes to the back. */
    val queuePosition: Long,
    /** Null while the catalogue has not said how large the file is. */
    val expectedSizeBytes: Long?,
    val bytesSoFar: Long,
    val localHash: String?,
    /** Set once the file is checked and waiting to be added; null otherwise. */
    val stagedFilePath: String?,
    val libraryBookId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val attempts: Int,
)

object CatalogueAcquisitionLimits {
    /** At most this many downloads run at once; the rest wait in request order. */
    const val MAX_RUNNING: Int = 2

    /** A finished download stays listed this long unless it is purged sooner. */
    const val FINISHED_RETENTION_MILLIS: Long = 24L * 60 * 60 * 1000

    const val MAX_TITLE_LENGTH: Int = 500
    const val MAX_AUTHOR_LENGTH: Int = 300
    const val MAX_CATALOGUE_NAME_LENGTH: Int = 200

    /** A longer cover reference (an inline image, say) is dropped, not cut. */
    const val MAX_COVER_REFERENCE_LENGTH: Int = 2048
    const val MAX_IDENTITY_LENGTH: Int = 2048

    /** A longer key or listing address cannot be cut without changing what it means. */
    const val MAX_KEY_LENGTH: Int = 4096
}
