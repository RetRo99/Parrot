package com.retro99.database.api.catalogue

/** Provenance of catalogue-acquired books. It outlives the catalogue it names. */
interface CatalogueBookSourcesDatabase {
    suspend fun insert(source: CatalogueBookSourceEntity)

    /** Does nothing when a row with this id exists, so it is safe to repeat. */
    suspend fun insertIfAbsent(source: CatalogueBookSourceEntity)

    /**
     * Rows of [sourceId] whose publication key or detail identity is one of [identities] and
     * whose library book still exists, oldest first.
     */
    suspend fun findInLibrary(sourceId: String, identities: Collection<String>): List<CatalogueLibraryMatch>

    suspend fun getForBook(libraryBookId: String): List<CatalogueBookSourceEntity>

    suspend fun getForSource(sourceId: String): List<CatalogueBookSourceEntity>

    /** How many different library books came from this catalogue. */
    suspend fun countBooksForSource(sourceId: String): Long
}

/** One acquired publication that is still in the library. */
data class CatalogueLibraryMatch(
    val publicationKey: String,
    val detailIdentity: String?,
    val libraryBookId: String,
)
