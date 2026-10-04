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

    /** Where the position came from: `user`, `restore`, `remote`, `linked_copy` or `manual`. */
    val origin: String
        get() = ORIGIN_USER

    /** When the reading happened (ISO-8601), which isn't necessarily when it was saved. */
    val observedAt: String?
        get() = null

    /** JSON `{"before": "...", "after": "..."}` for ebook positions, local only. */
    val textAnchor: String?
        get() = null

    /**
     * Audiobooks: the time from the start of the book. [audioTimestampMs] and [chapterIndex]
     * stay the offset in the current file and its index; [totalDurationMs] and
     * [totalProgression] are whole-book. Null when the files' lengths aren't known.
     */
    val bookTimeMs: Long?
        get() = null

    /** Audiobookshelf's raw `ebookLocation` (CFI or JSON locator), so a push mirrors its shape. */
    val ebookLocationRaw: String?
        get() = null

    /** Cloud-originating installation identity and display label, when available. */
    val sourceDeviceId: String?
        get() = null
    val deviceName: String?
        get() = null

    companion object {
        const val ORIGIN_USER = "user"
        const val ORIGIN_RESTORE = "restore"
        const val ORIGIN_REMOTE = "remote"
        const val ORIGIN_LINKED_COPY = "linked_copy"
        const val ORIGIN_MANUAL = "manual"
    }
}
