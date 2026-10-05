package com.retro99.books.domain.model.links

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.books.domain.model.BookHome
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkedCopyTest {

    private val library = testLibraryBook("b1", inParrot = true)
    private val storyteller = testServerBook("s1", hasAudiobook = true)
    private val audiobookshelf = testServerBook("a1", ServerType.Audiobookshelf)
    private val unlinked = testServerBook("s2")
    private val books = listOf(audiobookshelf, storyteller, library, unlinked)

    @Test
    fun `linkedCopiesOf lists the other resolved copies in home order`() {
        // Given: the link also names a copy no configured server has
        val links = listOf(
            testLink("link-1", "library:b1", "storyteller:s1", "audiobookshelf:a1"),
            testLink("link-2", "storyteller:gone", "library:b7"),
        )

        // When
        val copies = linkedCopiesOf(storyteller, books, links)

        // Then
        assertEquals(
            listOf(
                LinkedCopy(
                    key = CopyKey(CopySource.Library, "b1"),
                    serverId = LOCAL_SERVER_ID,
                    uuid = "b1",
                    title = "Book b1",
                    home = BookHome.ParrotCloud,
                    hasEbook = true,
                    hasAudiobook = false,
                    hasReadaloud = false,
                    isDownloaded = true,
                    searchTerms = listOf("Book b1"),
                ),
                LinkedCopy(
                    key = CopyKey(CopySource.Audiobookshelf, "a1"),
                    serverId = "audiobookshelf-1",
                    uuid = "a1",
                    title = "Book a1",
                    home = BookHome.Audiobookshelf,
                    hasEbook = true,
                    hasAudiobook = false,
                    hasReadaloud = false,
                    isDownloaded = false,
                    searchTerms = listOf("Book a1"),
                ),
            ),
            copies,
        )
    }

    @Test
    fun `linkedCopiesOf is empty for a book that is not linked or whose copies are gone`() {
        // Given
        val links = listOf(testLink("link-1", "storyteller:s2", "library:gone"))

        // When
        val forUnlinked = linkedCopiesOf(library, books, links)
        val forUnresolved = linkedCopiesOf(unlinked, books, links)

        // Then
        assertEquals(emptyList(), forUnlinked)
        assertEquals(emptyList(), forUnresolved)
    }

    @Test
    fun `linkPickerCandidates offers books from other sources that are not linked to it yet`() {
        // Given
        val links = listOf(testLink("link-1", "library:b1", "storyteller:s1"))

        // When
        val candidates = linkPickerCandidates(storyteller, books, links)

        // Then: no Storyteller books, and not the library copy it is already linked to
        assertEquals(listOf("a1"), candidates.map { book -> book.uuid })
    }

    @Test
    fun `picker excludes an otherwise different source when its group collides`() {
        val links = listOf(
            testLink("first", "library:b1", "storyteller:s1"),
            testLink("second", "audiobookshelf:a1", "storyteller:s2"),
        )
        assertEquals(emptyList(), linkPickerCandidates(library, books, links))
    }

    @Test
    fun `picker excludes the source already present even when current version is different`() {
        val links = listOf(testLink("first", "library:b1", "storyteller:s1"))
        assertEquals(listOf("a1"), linkPickerCandidates(library, books, links).map { it.uuid })
    }
}
