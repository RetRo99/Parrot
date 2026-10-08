package com.retro99.catalogue.data

import com.retro99.catalogue.domain.CatalogueEntryIdentity
import com.retro99.catalogue.domain.CatalogueLibraryLookup
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
    ): Map<CatalogueEntryIdentity, String> {
        val profileId = activeProfileId() ?: return emptyMap()
        if (entries.isEmpty()) return emptyMap()
        val identities = entries.flatMap { entry -> entry.identities() }
        val matches = try {
            session.withProfile(profileId) { sources.findInLibrary(sourceId, identities) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            return emptyMap()
        }
        // Oldest acquisition first, so an entry acquired twice names the same book every time.
        val bookByIdentity = mutableMapOf<String, String>()
        matches.forEach { match ->
            listOfNotNull(match.publicationKey, match.detailIdentity).forEach { identity ->
                if (identity !in bookByIdentity) bookByIdentity[identity] = match.libraryBookId
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
