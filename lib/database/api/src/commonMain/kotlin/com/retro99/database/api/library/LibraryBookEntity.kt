package com.retro99.database.api.library

/**
 * A book in your library. [libraryBookId] is a random UUID that never changes. The
 * source hash is a duplicate hint and never identifies the book.
 */
data class LibraryBookEntity(
    val libraryBookId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val coverPath: String? = null,
    val publicationDate: String? = null,
    val sourceContentHash: String? = null,
    val sourceContentHashAlgorithm: String? = null,
    val addedAt: String,
    val lastOpenedAt: String? = null,
    val remoteRevision: Long? = null,
    val deletedAt: String? = null,
    val metadataJson: String? = null,
)
