package com.retro99.catalogue.domain

/**
 * How one catalogue entry is known. A listing entry and the edition downloaded from it can
 * carry different identities, so an entry matches an acquired book by either of them.
 *
 * @param detailIdentity the identity of the listing or detail entry, when it is not the
 *   publication key itself
 */
data class CatalogueEntryIdentity(
    val publicationKey: String,
    val detailIdentity: String? = null,
)

/** Which catalogue entries are already books in your library ("In your library"). */
interface CatalogueLibraryLookup {
    /** Book-page status keeps the original acquisition time even after Downloads is purged. */
    suspend fun libraryDetailsFor(sourceId: String, entries: Collection<CatalogueEntryIdentity>): Map<CatalogueEntryIdentity, CatalogueLibraryBook> =
        libraryBooksFor(sourceId, entries).mapValues { CatalogueLibraryBook(it.value, null) }
    /** The library book acquired from [sourceId] for [entry], or null. */
    suspend fun libraryBookFor(sourceId: String, entry: CatalogueEntryIdentity): String? =
        libraryBooksFor(sourceId, listOf(entry))[entry]

    /**
     * One answer for a whole page. Only entries that are in the library are in the result; a
     * book that was acquired and has since been removed from the library is not.
     */
    suspend fun libraryBooksFor(
        sourceId: String,
        entries: Collection<CatalogueEntryIdentity>,
    ): Map<CatalogueEntryIdentity, String>
}

data class CatalogueLibraryBook(val libraryBookId: String, val acquiredAt: Long?)
