package com.retro99.database.api.books

/**
 * Position entity interface for database storage.
 * Stores the current reading position and progress for a book.
 * Matches the structure of PositionDomainModel.
 */
interface PositionEntity {
    val bookUuid: String
    val libraryBookId: String
        get() = bookUuid
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

    // Location fields
    val audioTimestampMs: Long?
    val chapterIndex: Int?
    val progression: Double?
    val totalChapters: Int?
    val totalDurationMs: Long?
    val totalProgression: Double?
    val position: Int?
}
