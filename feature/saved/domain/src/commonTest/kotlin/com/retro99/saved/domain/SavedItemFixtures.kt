package com.retro99.saved.domain

import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedAudioPosition
import com.retro99.saved.domain.model.SavedBookRef
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.model.SavedLocation
import com.retro99.saved.domain.model.TextAnchor
import kotlin.time.Instant

internal val Book = SavedBookRef(key = "library:book-a", uuid = "book-a", title = "The Lantern Ferry", author = "A. Writer")

internal fun item(
    id: String,
    type: SavedItemType = SavedItemType.Highlight,
    quote: String? = "Quote $id",
    chapter: String? = "8. The Crossing",
    totalProgression: Double? = 0.62,
    color: HighlightColor? = if (type == SavedItemType.Highlight) HighlightColor.Amber else null,
    note: String? = null,
    audioMs: Long? = null,
    createdAt: String = "2026-10-01T10:00:00Z",
) = SavedItem(
    id = id,
    book = Book,
    type = type,
    location = SavedLocation(
        href = "chapter8.xhtml",
        mediaType = "application/xhtml+xml",
        progression = 0.5,
        totalProgression = totalProgression,
        position = null,
        chapterTitle = chapter,
    ),
    anchor = quote?.let { text -> TextAnchor(before = null, quote = text, after = null) },
    color = color,
    note = note,
    audio = audioMs?.let { ms -> SavedAudioPosition(href = null, offsetMs = ms) },
    snippetPending = false,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(createdAt),
    remoteRevision = null,
)
