package com.retro99.catalogue.domain

/** A downloaded file that passed every check and is waiting in staging. */
data class StagedCatalogueBook(
    val requestId: String,
    /** Absolute path of the staged file. It ends in `.epub`. */
    val path: String,
    /** Lower-case hex SHA-256 of the file's bytes. */
    val contentHash: String,
    val sizeBytes: Long,
    val sourceId: String,
    val publicationKey: String,
    val representationKey: String,
    val detailIdentity: String?,
    val catalogueName: String,
) {
    override fun toString() = "StagedCatalogueBook($requestId, $sizeBytes bytes)"
}

sealed interface CatalogueBookAddResult {
    /** The file is in the library as [libraryBookId]; the staged file has been consumed. */
    data class Added(val libraryBookId: String) : CatalogueBookAddResult

    /** Nothing was done. The request stays "adding" and the staged file stays where it is. */
    data object NotAddedYet : CatalogueBookAddResult

    /** The file could not be added. [reason] is storage or invalid. */
    data class Failed(val reason: AcquisitionFailureReason) : CatalogueBookAddResult
}

/**
 * Moves a staged catalogue download into the library. The queue only knows this interface,
 * so the library import can be swapped or faked.
 */
interface CatalogueBookAdder {
    suspend fun add(profileId: String, book: StagedCatalogueBook): CatalogueBookAddResult
}
