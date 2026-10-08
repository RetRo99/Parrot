package com.retro99.server.opds

import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueDocumentEntity
import com.retro99.database.api.catalogue.CatalogueDocumentsDatabase
import com.retro99.opds.api.OPDS_ACCEPT_MEDIA_TYPES
import com.retro99.opds.api.OpdsCacheEntry
import com.retro99.opds.api.OpdsCacheKey
import com.retro99.opds.api.OpdsFeedCache
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsBudgets
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

/**
 * Catalogue pages you opened, kept in the profile's database so they can be shown when the
 * catalogue cannot be reached.
 *
 * - A page is found again only under the same catalogue and access generation it was saved
 *   under; the generation changes with every change of account details. Saving a page drops
 *   that catalogue's pages from any other generation.
 * - All pages of a profile together stay within [maxBytes]; the ones saved longest ago go first.
 * - A `no-store` page and a page over [maxPageBytes] are never saved. Book files never come
 *   through here at all.
 * - Only the profile that is open is read or written. For any other, nothing is saved and
 *   nothing is found.
 */
internal class SavedPagesFeedCache(
    private val session: ProfileDatabaseSession,
    private val documents: CatalogueDocumentsDatabase,
    private val maxBytes: Long = OpdsBudgets.MAX_SAVED_PAGES_BYTES,
    private val maxPageBytes: Long = OpdsBudgets.MAX_RESPONSE_BYTES,
) : OpdsFeedCache {

    override suspend fun load(key: OpdsCacheKey): OpdsCacheEntry? {
        if (!key.isCataloguePage()) return null
        val saved = inProfile(key.profileId) { documents.get(key.serverId, key.accessGeneration, key.url) } ?: return null
        return OpdsCacheEntry(
            body = OpdsPayload(saved.contentType, saved.payload),
            validators = buildMap {
                saved.eTag?.let { put(HttpHeaders.IfNoneMatch, it) }
                saved.lastModified?.let { put(HttpHeaders.IfModifiedSince, it) }
            },
            storedAtMillis = saved.storedAt,
            effectiveUrl = saved.effectiveUrl ?: saved.requestUrl,
        )
    }

    override suspend fun store(key: OpdsCacheKey, entry: OpdsCacheEntry) {
        if (!key.isCataloguePage()) return
        val size = entry.body.bytes.size.toLong()
        inProfile(key.profileId) {
            if (entry.cacheControl.hasNoStore() || size > maxPageBytes || size > maxBytes) {
                documents.delete(key.serverId, key.accessGeneration, key.url)
                return@inProfile
            }
            documents.deleteOtherGenerations(key.serverId, key.accessGeneration)
            documents.upsert(
                CatalogueDocumentEntity(
                    sourceId = key.serverId,
                    accessGeneration = key.accessGeneration,
                    requestUrl = key.url,
                    contentType = entry.body.mediaTypeHeader,
                    eTag = entry.validators[HttpHeaders.IfNoneMatch],
                    lastModified = entry.validators[HttpHeaders.IfModifiedSince],
                    storedAt = entry.storedAtMillis,
                    payload = entry.body.bytes,
                    effectiveUrl = entry.effectiveUrl.takeIf { it != key.url },
                ),
            )
            var total = documents.totalSizeBytes()
            while (total > maxBytes) {
                // The page just saved is the newest, so it is never the one that goes.
                val oldest = documents.oldestKeys(EVICTION_BATCH).takeIf { it.isNotEmpty() } ?: break
                for (page in oldest) {
                    documents.delete(page.sourceId, page.accessGeneration, page.requestUrl)
                    total -= page.sizeBytes
                    if (total <= maxBytes) break
                }
            }
        }
    }

    override suspend fun invalidate(key: OpdsCacheKey) {
        if (!key.isCataloguePage()) return
        inProfile(key.profileId) { documents.delete(key.serverId, key.accessGeneration, key.url) }
    }

    /** Saved pages outlive a catalogue session. They go per catalogue, through [clearSource]. */
    override suspend fun clearAll() = Unit

    /** Every page saved for this catalogue, under any account details. */
    suspend fun clearSource(profileId: String, sourceId: String) {
        inProfile(profileId) { documents.deleteForSource(sourceId) }
    }

    /** The table is keyed by address alone, so only the one way catalogue pages are asked for is saved. */
    private fun OpdsCacheKey.isCataloguePage() = representation == CATALOGUE_PAGE

    /** `withProfile` refuses a profile that is not the open one: for the cache that is a miss. */
    private suspend fun <T> inProfile(profileId: String, operation: suspend () -> T): T? = try {
        session.withProfile(profileId, operation)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalStateException) {
        null
    }

    private fun List<String>.hasNoStore() =
        flatMap { it.split(',') }.any { it.trim().substringBefore('=').equals("no-store", ignoreCase = true) }

    private companion object {
        const val EVICTION_BATCH = 16L
        val CATALOGUE_PAGE = OPDS_ACCEPT_MEDIA_TYPES.joinToString(", ") { it.trim().lowercase() }
    }
}
