package com.retro99.books.domain.model.links

import com.retro99.books.domain.model.BookHome
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupLinkedBooksTest {

    private val parrot = testLibraryBook("b1", inParrot = true, onDevice = false)
    private val storyteller = testServerBook("s1", subtitle = "There and Back Again")
    private val audiobookshelf = testServerBook("a1", ServerType.Audiobookshelf)
    private val unlinked = testServerBook("s2")

    @Test
    fun `three copies in one link give one entry and unlinked books pass through`() {
        // Given
        val books = listOf(audiobookshelf, storyteller, unlinked, parrot)
        val links = listOf(
            testLink("link-1", "library:b1", "storyteller:s1", "audiobookshelf:a1"),
        )

        // When
        val grouped = groupLinkedBooks(
            books = books,
            links = links,
            downloaded = setOf(audiobookshelf.copyKey()),
        )

        // Then
        assertEquals(listOf("b1", "s2"), grouped.map { book -> book.uuid })
        val primary = grouped.first()
        assertEquals(
            listOf(
                Triple("s1", BookHome.Storyteller, false),
                Triple("a1", BookHome.Audiobookshelf, true),
            ),
            primary.linkedCopies.map { copy -> Triple(copy.uuid, copy.home, copy.isDownloaded) },
        )
        assertEquals(emptyList(), grouped.last().linkedCopies)
        assertEquals(unlinked, grouped.last())
    }

    @Test
    fun `the primary copy follows what was opened last`() {
        // Given
        val links = listOf(testLink("link-1", "library:b1", "storyteller:s1"))

        // When
        val grouped = groupLinkedBooks(
            books = listOf(parrot, storyteller),
            links = links,
            lastOpened = mapOf(storyteller.copyKey() to 10L),
        )

        // Then
        assertEquals("s1", grouped.single().uuid)
        assertEquals(listOf("b1"), grouped.single().linkedCopies.map { copy -> copy.uuid })
    }

    @Test
    fun `an unresolved member does not create an empty entry`() {
        // Given: one link has only one copy this device can see, the other has none
        val links = listOf(
            testLink("link-1", "storyteller:s1", "audiobookshelf:gone"),
            testLink("link-2", "library:gone", "audiobookshelf:also-gone"),
        )

        // When
        val grouped = groupLinkedBooks(books = listOf(storyteller, unlinked), links = links)

        // Then
        assertEquals(listOf(storyteller, unlinked), grouped)
    }

    @Test
    fun `linked copies carry what a search can match`() {
        // Given
        val links = listOf(testLink("link-1", "library:b1", "storyteller:s1"))

        // When
        val grouped = groupLinkedBooks(books = listOf(parrot, storyteller), links = links)

        // Then
        val copy = grouped.single().linkedCopies.single()
        assertEquals(listOf("Book s1", "There and Back Again"), copy.searchTerms)
    }
}
