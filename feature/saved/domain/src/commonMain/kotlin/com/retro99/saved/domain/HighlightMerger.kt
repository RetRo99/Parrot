package com.retro99.saved.domain

import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedLocation
import com.retro99.saved.domain.model.TextAnchor
import kotlin.time.Instant

/**
 * Selecting inside or across existing highlights extends them instead of stacking a
 * second highlight on top. The reader works out which highlights the new selection
 * overlaps and the combined range in the page; this decides what is saved.
 */
object HighlightMerger {

    data class Result(
        /** The one highlight that covers the whole range, new or extended. */
        val survivor: SavedItem,
        /** Highlights folded into [survivor]; they are deleted. */
        val absorbedIds: List<String>,
    )

    /**
     * @param fresh the highlight the selection would make on its own.
     * @param overlapping existing highlights the selection overlaps or touches.
     * @param mergedAnchor the text of the combined range.
     * @param mergedLocation where the combined range starts.
     * @param pickedColor the colour the reader just chose; null keeps the oldest highlight's.
     */
    fun merge(
        fresh: SavedItem,
        overlapping: List<SavedItem>,
        mergedAnchor: TextAnchor,
        mergedLocation: SavedLocation,
        pickedColor: HighlightColor?,
        now: Instant,
    ): Result {
        if (overlapping.isEmpty()) {
            return Result(
                survivor = fresh.copy(color = pickedColor ?: fresh.color, updatedAt = now),
                absorbedIds = emptyList(),
            )
        }
        // The oldest highlight keeps its id, so its note and sync history carry on.
        val ordered = overlapping.sortedWith(compareBy<SavedItem> { item -> item.createdAt }.thenBy { item -> item.id })
        val oldest = ordered.first()
        val notes = ordered.mapNotNull { item -> item.note?.trim()?.takeIf { note -> note.isNotEmpty() } }
            .distinct()
        val survivor = oldest.copy(
            anchor = mergedAnchor,
            location = mergedLocation.copy(chapterTitle = mergedLocation.chapterTitle ?: oldest.location.chapterTitle),
            color = pickedColor ?: oldest.color ?: HighlightColor.Default,
            note = notes.takeIf { joined -> joined.isNotEmpty() }?.joinToString(separator = "\n\n"),
            updatedAt = now,
        )
        return Result(
            survivor = survivor,
            absorbedIds = ordered.drop(1).map { item -> item.id },
        )
    }
}
