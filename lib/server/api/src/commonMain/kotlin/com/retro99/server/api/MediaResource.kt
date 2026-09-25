package com.retro99.server.api

/** A single local or remote resource belonging to a book. */
data class MediaResource(
    val mediaType: String,
    val localPath: String? = null,
    val remoteAvailability: RemoteFileAvailability = RemoteFileAvailability.None,
    val size: Long? = null,
    val contentHash: String? = null,
    val contentHashAlgorithm: String? = null,
    val localOrigin: String? = null,
    val cloudBookFileId: String? = null,
    /** Exact adapter-owned identity; never derive this from a path or media type. */
    val nativeResourceId: String? = null,
    val resourceRevision: String? = null,
    val format: String? = null,
)
