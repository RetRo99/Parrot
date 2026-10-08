package com.retro99.server.api

/** Invalidates in-flight catalogue work, private caches and search descriptors. No book deletion. */
interface CatalogueWorkController {
    /** The catalogue was turned off or moved, or got new account details. */
    suspend fun cancel(profileId: String, sourceId: String)

    /**
     * The catalogue was removed, or its account details were. Nothing that was fetched for it
     * may stay, including what [cancel] keeps for a later sign-in.
     */
    suspend fun forget(profileId: String, sourceId: String) = cancel(profileId, sourceId)
}
