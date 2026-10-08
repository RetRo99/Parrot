package com.retro99.catalogue.data

import com.retro99.catalogue.domain.CatalogueEntryIdentity
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.catalogue.domain.CatalogueLibraryBook
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import kotlinx.coroutines.CancellationException

/**
 * Answers from the provenance rows of the open profile, joined against books that still
 * exist. With no profile open, or the profile closing meanwhile, nothing is in the library.
 */
internal class DatabaseCatalogueLibraryLookup(
    private val session: ProfileDatabaseSession,
    private val sources: CatalogueBookSourcesDatabase,
    private val activeProfileId: () -> String?,
) : CatalogueLibraryLookup {

    override suspend fun libraryBooksFor(
        sourceId: String,
        entries: Collection<CatalogueEntryIdentity>,
    ): Map<CatalogueEntryIdentity, String> = lookup(sourceId, entries, dates = false).mapValues { it.value.libraryBookId }

    override suspend fun libraryDetailsFor(sourceId: String, entries: Collection<CatalogueEntryIdentity>): Map<CatalogueEntryIdentity, CatalogueLibraryBook> = lookup(sourceId, entries, dates = true)

    private suspend fun lookup(sourceId: String, entries: Collection<CatalogueEntryIdentity>, dates: Boolean): Map<CatalogueEntryIdentity, CatalogueLibraryBook> {
        val profileId = activeProfileId() ?: return emptyMap()
        if (entries.isEmpty()) return emptyMap()
        val identities = entries.flatMap { entry -> entry.identities() }
        val (matches, acquiredAt) = try {
            session.withProfile(profileId) {
                val matches = sources.findInLibrary(sourceId, identities)
                val times = if (!dates) emptyMap() else matches.map { it.libraryBookId }.distinct().associateWith { id ->
                    sources.getForBook(id).filter { it.sourceId == sourceId && (it.publicationKey in identities || it.detailIdentity in identities) }.minOfOrNull { it.acquiredAt }
                }
                matches to times
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            return emptyMap()
        }
        // Oldest acquisition first, so an entry acquired twice names the same book every time.
        val bookByIdentity = mutableMapOf<String, CatalogueLibraryBook>()
        matches.forEach { match ->
            listOfNotNull(match.publicationKey, match.detailIdentity).forEach { identity ->
                if (identity !in bookByIdentity) bookByIdentity[identity] = CatalogueLibraryBook(match.libraryBookId, acquiredAt[match.libraryBookId])
            }
        }
        return buildMap {
            entries.forEach { entry ->
                entry.identities().firstNotNullOfOrNull { identity -> bookByIdentity[identity] }
                    ?.let { bookId -> put(entry, bookId) }
            }
        }
    }

    private fun CatalogueEntryIdentity.identities(): List<String> = listOfNotNull(publicationKey, detailIdentity)
}
