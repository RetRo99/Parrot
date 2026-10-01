package com.retro99.database.api.books

/**
 * Position entity interface for database storage.
 * Stores the current reading position and progress for a book.
 * Matches the structure of PositionDomainModel.
 */
interface PositionEntity {
    val bookUuid: String

    /** The book id for books in your library, NULL for server books (I4). */
    val libraryBookId: String?
        get() = null
    /** Local version used to guard acknowledgements for older uploads. */
    val localGeneration: Long
        get() = 0L
    val remoteRevision: Long?
        get() = null
    val timestamp: Long?
    val createdAt: String?
    val updatedAt: String?

    // Locator fields
    val locatorHref: String?
    val locatorType: String?
    val locatorTitle: String?
    val locatorTarget: Int?
    val cssSelector: String?
        get() = null

    // Location fields
    val audioTimestampMs: Long?
    val chapterIndex: Int?
    val progression: Double?
    val totalChapters: Int?
    val totalDurationMs: Long?
    val totalProgression: Double?
    val position: Int?
}
