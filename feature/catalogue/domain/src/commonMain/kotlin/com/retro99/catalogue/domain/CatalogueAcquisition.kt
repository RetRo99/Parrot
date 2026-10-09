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
    /** The catalogue's rights text and `updated` value for the book, kept with the book once added. */
    val rightsText: String? = null,
    val catalogueUpdated: String? = null,
) {
    override fun toString() = "CatalogueAcquisitionRequest(redacted)"
}

/** What asking for a download led to. */
sealed interface CatalogueRequestOutcome {
    /** A request is stored: a new one, or the unfinished one that was already there. */
    data class Queued(val acquisition: CatalogueAcquisition) : CatalogueRequestOutcome

    /** This book was already acquired from this catalogue and is in your library. Nothing was started. */
    data class InLibrary(val libraryBookId: String) : CatalogueRequestOutcome
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
    /**
     * Null while the catalogue has not said how large the file is. For a "too large" failure
     * this is the size that was over [CatalogueAcquisitionLimits.MAX_FILE_BYTES], when the
     * catalogue declared one.
     */
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
    /** For a storage failure: how many bytes had to be free on this device, when known. */
    val neededBytes: Long? = null,
)

object CatalogueAcquisitionLimits {
    /** At most this many downloads run at once; the rest wait in request order. */
    const val MAX_RUNNING: Int = 2

    /** The largest file Parrot adds: 512 MiB. The "too large" message names this limit. */
    const val MAX_FILE_BYTES: Long = 512L * 1024 * 1024

    /** A finished download stays listed this long unless it is purged sooner. */
    const val FINISHED_RETENTION_MILLIS: Long = 24L * 60 * 60 * 1000

    const val MAX_TITLE_LENGTH: Int = 500
    const val MAX_AUTHOR_LENGTH: Int = 300
    const val MAX_CATALOGUE_NAME_LENGTH: Int = 200

    const val MAX_RIGHTS_LENGTH: Int = 2000
    const val MAX_CATALOGUE_UPDATED_LENGTH: Int = 64

    /** A longer cover reference (an inline image, say) is dropped, not cut. */
    const val MAX_COVER_REFERENCE_LENGTH: Int = 2048
    const val MAX_IDENTITY_LENGTH: Int = 2048

    /** A longer key or listing address cannot be cut without changing what it means. */
    const val MAX_KEY_LENGTH: Int = 4096
}
