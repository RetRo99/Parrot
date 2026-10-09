package com.retro99.catalogue.ui.browse

import com.retro99.server.api.*
import kotlin.test.*

class LinkedBookRuleTest {
    @Test fun flaggedResultFeedHasBooksButRootAndPureNavigationStayFolders() {
        val entry = folder("Title", "details").copy(content = CatalogueDescription(CatalogueDescription.Format.Text, text("Author")))
        val list = feed(folders = listOf(entry), next = "next")
        assertEquals(listOf(entry), linkedBookEntries(true, list))
        assertTrue(linkedBookEntries(false, list).isEmpty())
        assertTrue(linkedBookEntries(true, feed(folders = listOf(entry))).isEmpty())
        assertEquals(listOf(entry), linkedBookEntries(true, feed(folders = listOf(entry)), searchResults = true))
        val navigation = entry.copy(links = listOf(link("authors", href = "$ORIGIN/authors?query=title")))
        assertEquals(listOf(entry), linkedBookEntries(true, feed(folders = listOf(navigation, entry)), searchResults = true))
    }
}
