package com.retro99.database.api.books

/**
 * Media file entity interface for database storage.
 * Used for ebook and audiobook files.
 */
interface MediaFileEntity {
    val uuid: String
    val bookUuid: String
    val type: String // "ebook" or "audiobook"
    val filepath: String?
    val missing: Int?
    val size: Long?
    val createdAt: String?
    val updatedAt: String?
}

