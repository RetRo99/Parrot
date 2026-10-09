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
    /**
     * The file is in the library as [libraryBookId] and the staged file has been consumed.
     * When the library already held these exact bytes, this is that book, unchanged.
     */
    data class Added(val libraryBookId: String) : CatalogueBookAddResult

    /**
     * The file could not be added and is still in staging. [reason] is storage or invalid;
     * [neededBytes] is the room a storage failure was short of, when that is known.
     */
    data class Failed(val reason: AcquisitionFailureReason, val neededBytes: Long? = null) : CatalogueBookAddResult
}

/**
 * Moves a staged catalogue download into the library. The queue only knows this interface,
 * so the library import can be swapped or faked.
 */
interface CatalogueBookAdder {
    suspend fun add(profileId: String, book: StagedCatalogueBook): CatalogueBookAddResult

    /**
     * Call when a profile's downloads are loaded after a restart, before [findAddedBook]:
     * finishes or undoes whatever an add was doing to the library when Parrot closed.
     */
    suspend fun settleInterruptedAdds(profileId: String)

    /**
     * The library book that holds a file with [contentHash] on this device, or null. Tells a
     * request that was being added when Parrot closed whether its book made it in.
     */
    suspend fun findAddedBook(profileId: String, contentHash: String): String?
}
