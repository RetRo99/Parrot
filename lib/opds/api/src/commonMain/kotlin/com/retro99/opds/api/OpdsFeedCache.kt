package com.retro99.opds.api

/**
 * Bounded feed cache (plan §3.2/§4; §10.10: a small persisted cache of
 * documents the user opened ships later; Phase 1 provides the contracted
 * interface and the in-memory implementation).
 *
 * Keys are sensitive-data-aware (plan §4: auth-sensitive keys include
 * profile, server/access generation, request URL, and representation);
 * entries honor `no-store` (the loader, not the cache, checks directives).
 */
interface OpdsFeedCache {
    suspend fun load(key: OpdsCacheKey): OpdsCacheEntry?

    suspend fun store(key: OpdsCacheKey, entry: OpdsCacheEntry)

    suspend fun invalidate(key: OpdsCacheKey)

    /** Bounded extra hook for profile/server-scoped clear (§3.4 lifecycle). */
    suspend fun clearAll()
}

data class OpdsCacheKey(
    /** Full request URL. Never logged; part of the key material only. */
    val url: String,
    val profileId: String,
    val serverId: String,
    /** Access-state generation; changing credentials invalidates old entries (§3.4). */
    val accessGeneration: Int = 0,
    /** Representation discriminator: the exact accepted-media-type list, normalized. */
    val representation: String,
)

data class OpdsCacheEntry(
    val body: OpdsPayload,
    /** Response validators to send back as conditionals (ETag/Last-Modified). */
    val validators: Map<String, String>,
    val storedAtMillis: Long,
    /** The effective URL the cached body was fetched at (plan §4 base rule). */
    val effectiveUrl: String,
)
