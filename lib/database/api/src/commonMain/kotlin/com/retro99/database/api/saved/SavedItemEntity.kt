package com.retro99.database.api.saved

/**
 * A bookmark or highlight. A "note" is either one with [note] text.
 * [bookKey] is the portable copy key shared across devices; [bookUuid] is the local book id.
 */
interface SavedItemEntity {
    val id: String
    val bookKey: String
    val bookUuid: String
    val bookTitle: String?
    val bookAuthor: String?
    val type: String
    val href: String
    val mediaType: String?
    val progression: Double?
    val totalProgression: Double?
    val position: Int?
    val chapterTitle: String?
    val textBefore: String?
    val textQuote: String?
    val textAfter: String?
    val color: String?
    val note: String?
    val audioHref: String?
    val audioMs: Long?
    val snippetPending: Boolean
    val createdAt: String
    val updatedAt: String
    val deletedAt: String?
    val remoteRevision: Long?

    companion object {
        const val TYPE_BOOKMARK = "bookmark"
        const val TYPE_HIGHLIGHT = "highlight"
    }
}
