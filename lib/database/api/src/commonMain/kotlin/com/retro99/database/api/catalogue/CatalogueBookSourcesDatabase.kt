package com.retro99.database.api.catalogue

import kotlinx.coroutines.flow.Flow

/** Provenance of catalogue-acquired books. It outlives the catalogue it names. */
interface CatalogueBookSourcesDatabase {
    suspend fun insert(source: CatalogueBookSourceEntity)

    suspend fun getForBook(libraryBookId: String): List<CatalogueBookSourceEntity>

    /** The "already in your library" lookup. */
    suspend fun getForPublication(sourceId: String, publicationKey: String): List<CatalogueBookSourceEntity>

    suspend fun getForSource(sourceId: String): List<CatalogueBookSourceEntity>

    fun observeForSource(sourceId: String): Flow<List<CatalogueBookSourceEntity>>

    /** How many different library books came from this catalogue. */
    suspend fun countBooksForSource(sourceId: String): Long

    /** After a library merge: rows of [fromLibraryBookId] now belong to [toLibraryBookId]. */
    suspend fun moveToBook(fromLibraryBookId: String, toLibraryBookId: String)

    suspend fun deleteForBook(libraryBookId: String)

    suspend fun deleteAll()
}
