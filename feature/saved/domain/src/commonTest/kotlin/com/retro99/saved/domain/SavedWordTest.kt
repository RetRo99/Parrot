package com.retro99.saved.domain

import com.retro99.saved.domain.model.*
import kotlin.test.*

class SavedWordTest {
    @Test fun wordsHaveTheirOwnFilterAndExportTheirGloss() {
        val word = item("word", type = SavedItemType.Word).copy(word = SavedWord("mice", "mouse", "en", "a small rodent", "noun"))
        assertTrue(SavedFilter.Words.matches(word))
        assertFalse(SavedFilter.Bookmarks.matches(word))
        assertFalse(SavedFilter.Highlights.matches(word))
        assertEquals(1, SavedCounts.of(listOf(word)).words)
        assertEquals(1, SavedCounts.of(listOf(word)).total)
        val labels = SavedItemsExport.Labels("Bookmark", "Highlight", "Note", "listening, %s", "Chapter")
        for (text in listOf(SavedItemsExport.plainText("Book", null, listOf(word), labels), SavedItemsExport.markdown("Book", null, listOf(word), labels))) {
            assertContains(text, "mouse — a small rodent")
            assertContains(text, "Word · 62%")
            assertContains(text, "Open English WordNet")
        }
    }

    @Test fun futureTypesDoNotBecomeBookmarks() { assertNull(SavedItemType.fromId("future-type")) }

    @Test fun savedStateOnlyAppliesToTheCurrentOccurrence() {
        val anchor = TextAnchor("the ", "mice", " ran")
        val word = item("word", type = SavedItemType.Word).copy(
            word = SavedWord("mice", "mouse", "en", "a small rodent", "noun"), anchor = anchor)
        assertTrue(word.isWordAt("MOUSE", word.location.href, anchor))
        assertFalse(word.isWordAt("mouse", "another-chapter.xhtml", anchor))
        assertFalse(word.isWordAt("mouse", word.location.href, anchor.copy(before = "two ")))
        assertFalse(word.isWordAt("rat", word.location.href, anchor))
        assertFalse(word.copy(type = SavedItemType.Bookmark).isWordAt("mouse", word.location.href, anchor))
    }
}
