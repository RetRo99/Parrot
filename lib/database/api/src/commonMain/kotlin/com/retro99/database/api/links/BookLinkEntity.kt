package com.retro99.database.api.links

/**
 * A link groups copies of one book that live on different sources. [members] are portable
 * copy keys such as `library:<bookId>` or `storyteller:<uuid>`. A link with [deletedAt] set
 * is a tombstone and has no members.
 */
data class BookLinkEntity(
    val linkId: String,
    val members: List<String>,
    val createdAt: String,
    val updatedAt: String,
    val remoteRevision: Long? = null,
    val deletedAt: String? = null,
)
