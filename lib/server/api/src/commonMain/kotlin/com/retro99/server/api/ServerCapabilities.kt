package com.retro99.server.api

/**
 * Describes what features a server type supports.
 * Used to conditionally show/hide UI features.
 */
data class ServerCapabilities(
    val supportsEbooks: Boolean,
    val supportsAudiobooks: Boolean,
    val supportsReadAloud: Boolean,
    val supportsReadingProgress: Boolean,
    val supportsCollections: Boolean,
    val supportsSeries: Boolean,
    val supportsSearch: Boolean,
    val supportsUserLibrary: Boolean,
    val supportsBookUpload: Boolean = false,
    val supportsBookDownload: Boolean = false,
    val supportsBookDeletion: Boolean = false,
    val supportsAutomaticSync: Boolean = false,
    val supportsOfflineMutationQueue: Boolean = false,
    val supportsCloudFileStatus: Boolean = false,
)

fun ServerType.getCapabilities(): ServerCapabilities = when (this) {
    ServerType.Storyteller -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = true,
        supportsReadAloud = true,
        supportsReadingProgress = true,
        supportsCollections = true,
        supportsSeries = true,
        supportsSearch = true,
        supportsUserLibrary = true,
    )
    ServerType.Audiobookshelf -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = true,
        supportsReadAloud = false,
        supportsReadingProgress = true,
        supportsCollections = true,
        supportsSeries = true,
        supportsSearch = true,
        supportsUserLibrary = true,
    )
    ServerType.Local -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = false,
        supportsReadAloud = false,
        supportsReadingProgress = true,
        supportsCollections = false,
        supportsSeries = false,
        supportsSearch = true,
        supportsUserLibrary = false,
    )
    ServerType.ParrotCloud -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = false,
        supportsReadAloud = false,
        supportsReadingProgress = true,
        supportsCollections = false,
        supportsSeries = false,
        supportsSearch = true,
        supportsUserLibrary = true,
        supportsAutomaticSync = true,
        supportsOfflineMutationQueue = true,
        supportsCloudFileStatus = true,
    )
}

fun ServerType.isManaged(): Boolean {
    return this == ServerType.Local || this == ServerType.ParrotCloud
}
