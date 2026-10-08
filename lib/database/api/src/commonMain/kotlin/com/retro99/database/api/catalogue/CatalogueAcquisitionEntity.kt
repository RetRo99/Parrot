package com.retro99.database.api.catalogue

/**
 * One catalogue download request. Timestamps are epoch milliseconds.
 *
 * [detailUrl] is where the publication is listed. It is kept only so the download link can be
 * looked up again, stays on this device and is cleared when the request completes.
 * [expectedSizeBytes] is the size the catalogue declared; on a "too large" failure it is the
 * size that was over the limit.
 */
data class CatalogueAcquisitionEntity(
    val requestId: String,
    val sourceId: String,
    val publicationKey: String,
    val detailIdentity: String?,
    val detailUrl: String?,
    val representationKey: String,
    val title: String,
    val author: String?,
    val coverReference: String?,
    val catalogueName: String,
    val state: String,
    val queuePosition: Long,
    val stagingPath: String?,
    val expectedSizeBytes: Long?,
    val bytesSoFar: Long,
    val localHash: String?,
    val libraryBookId: String?,
    val failureReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val attempts: Int,
    /** The catalogue's rights text and `updated` value when the request was made, for provenance. */
    val rightsText: String? = null,
    val catalogueUpdated: String? = null,
    /** For a storage failure: how many bytes had to be free, when that was known. */
    val neededBytes: Long? = null,
) {
    override fun toString(): String = "CatalogueAcquisitionEntity($requestId, $state)"

    companion object {
        const val STATE_WAITING = "waiting"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_CHECKING = "checking"
        const val STATE_ADDING = "adding"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
        const val STATE_INTERRUPTED = "interrupted"
    }
}
