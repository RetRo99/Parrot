package com.retro99.server.api

/** Invalidates in-flight catalogue work, private caches and search descriptors. No book deletion. */
interface CatalogueWorkController {
    suspend fun cancel(profileId: String, sourceId: String)
}
