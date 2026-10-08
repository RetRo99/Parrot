package com.retro99.database.api.catalogue

/**
 * Where a library book was acquired from. One book can have several of these.
 *
 * [catalogueOrigin] is scheme, host and port only. The catalogue's full address, download
 * links, headers and passwords never belong here.
 */
data class CatalogueBookSourceEntity(
    val id: String,
    val libraryBookId: String,
    val sourceId: String,
    val catalogueName: String,
    val catalogueOrigin: String,
    val publicationKey: String,
    val detailIdentity: String?,
    val selectedFormat: String,
    val rightsText: String?,
    val catalogueUpdated: String?,
    val contentHash: String,
    val acquiredAt: Long,
)
