package com.retro99.saved.ui.library

import com.retro99.saved.domain.model.SavedBookRef
import com.retro99.saved.domain.model.SavedFilter
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.model.SavedLocation
import com.retro99.saved.domain.model.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NotesHighlightsViewStateTest {
    @Test
    fun latestIsBoundedButSearchReturnsEveryMatch() {
        val items = (1..25).map { item(it.toString()) }
        assertEquals(20, NotesHighlightsViewState(items = items).latest.size)
        assertEquals(25, NotesHighlightsViewState(items = items, query = "QUOTE").latest.size)
    }

    @Test
    fun largeBookKeepsEveryItemAndFullCounts() {
        val items = (1..550).map { item(it.toString(), progress = it / 550.0) }
        val state = NotesHighlightsViewState(items = items.reversed(), bookKey = "library:book")
        assertEquals(20, state.latest.size)
        assertEquals(550, state.byBook.single().counts.total)
        assertEquals(items.map { it.id }, state.bookItems.map { it.id })
    }

    @Test
    fun filtersAndSearchApplyToBookCounts() {
        val state = NotesHighlightsViewState(
            items = listOf(item("1", note = "Remember this"), item("2"), item("3", key = "other")),
            filter = SavedFilter.Notes,
            query = "remember",
        )
        assertEquals(listOf("1"), state.latest.map { it.id })
        assertEquals(1, state.byBook.size)
        assertEquals(1, state.byBook.single().counts.total)
        assertEquals(1, state.byBook.single().counts.notes)
    }

    @Test
    fun bookListIsOrderedAndSearchDoesNotChangeExportItems() {
        val state = NotesHighlightsViewState(
            items = listOf(item("late", progress = 0.8), item("other", key = "other"),
                item("early", progress = 0.1, note = "Remember this")),
            bookKey = "library:book",
            query = "remember",
        )
        assertEquals(listOf("early", "late"), state.bookItems.map { it.id })
        assertEquals(listOf("early"), state.latest.map { it.id })
    }

    private fun item(
        id: String,
        key: String = "library:book",
        note: String? = null,
        progress: Double = 0.0,
    ) = SavedItem(
        id = id,
        book = SavedBookRef(key, "book", "Title", "Author"),
        type = SavedItemType.Bookmark,
        location = SavedLocation("chapter.xhtml", null, progress, progress, null, "Chapter"),
        anchor = TextAnchor(null, "Quote $id", null),
        color = null,
        note = note,
        audio = null,
        snippetPending = false,
        createdAt = Instant.parse("2026-10-04T00:00:00Z"),
        updatedAt = Instant.parse("2026-10-04T00:00:00Z"),
        remoteRevision = null,
    )
}
