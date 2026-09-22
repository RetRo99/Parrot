package com.retro99.database.api.library

interface LibraryBookEntity {
    val libraryBookId: String
    val contentHash: String?
    val contentHashAlgorithm: String?
        get() = null
    val title: String
    val author: String?
    val format: String
    val remoteRevision: Long?
        get() = null
    val deletedAt: String?
        get() = null
    val cloudBookId: String?
        get() = null
    val metadataJson: String?
        get() = null
}
