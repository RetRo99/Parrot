package com.retro99.saved.domain.model

import kotlin.time.Instant

enum class SavedItemType(val id: String) {
    Bookmark("bookmark"),
    Highlight("highlight"),
    ;

    companion object {
        fun fromId(id: String): SavedItemType = entries.firstOrNull { type -> type.id == id } ?: Bookmark
    }
}

/** The four highlight colours. Amber is the default, and the only one e-ink devices make. */
enum class HighlightColor(val id: String) {
    Amber("amber"),
    Rose("rose"),
    Sage("sage"),
    Sky("sky"),
    ;

    companion object {
        val Default = Amber

        fun fromId(id: String?): HighlightColor? = entries.firstOrNull { color -> color.id == id }
    }
}

/**
 * Where an item sits in the text: the quoted text plus a little context on each side.
 * It doesn't depend on layout, so it survives font and size changes.
 */
data class TextAnchor(
    val before: String?,
    val quote: String,
    val after: String?,
)

/** Where an item sits in the book, independent of the text. */
data class SavedLocation(
    val href: String,
    val mediaType: String?,
    val progression: Double?,
    val totalProgression: Double?,
    val position: Int?,
    val chapterTitle: String?,
)

/** A bookmark or highlight. A "note" is any item with [note] text. */
data class SavedItem(
    val id: String,
    val book: SavedBookRef,
    val type: SavedItemType,
    val location: SavedLocation,
    val anchor: TextAnchor?,
    val color: HighlightColor?,
    val note: String?,
    /** Set on bookmarks made while listening to a read-aloud book or audiobook. */
    val audio: SavedAudioPosition?,
    /** Migrated bookmark waiting for its first sentence. */
    val snippetPending: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val remoteRevision: Long?,
) {
    val hasNote: Boolean get() = !note.isNullOrBlank()

    /** The sentence or quote shown for the item; null until a migrated bookmark is resolved. */
    val text: String? get() = anchor?.quote?.takeIf { quote -> quote.isNotBlank() }

    val isListeningBookmark: Boolean get() = type == SavedItemType.Bookmark && audio != null
}

/** The book an item belongs to, with a title snapshot for books this device doesn't have. */
data class SavedBookRef(
    /** Portable copy key: library:<id>, storyteller:<uuid> or audiobookshelf:<itemId>. */
    val key: String,
    val uuid: String,
    val title: String?,
    val author: String?,
)

data class SavedAudioPosition(
    val href: String?,
    val offsetMs: Long,
)

enum class SavedFilter {
    All,
    Bookmarks,
    Highlights,
    Notes,
    ;

    fun matches(item: SavedItem): Boolean = when (this) {
        All -> true
        Bookmarks -> item.type == SavedItemType.Bookmark
        Highlights -> item.type == SavedItemType.Highlight
        Notes -> item.hasNote
    }
}

/** Parrot Cloud's per-item limits (see the saved items migration). */
object SavedItemLimits {
    const val MAX_QUOTE_CHARS = 1_500
    const val MAX_NOTE_CHARS = 2_000
    const val MAX_CONTEXT_CHARS = 64
}

data class SavedCounts(
    val bookmarks: Int,
    val highlights: Int,
    val notes: Int,
) {
    val total: Int get() = bookmarks + highlights

    companion object {
        fun of(items: List<SavedItem>) = SavedCounts(
            bookmarks = items.count { item -> item.type == SavedItemType.Bookmark },
            highlights = items.count { item -> item.type == SavedItemType.Highlight },
            notes = items.count { item -> item.hasNote },
        )
    }
}
