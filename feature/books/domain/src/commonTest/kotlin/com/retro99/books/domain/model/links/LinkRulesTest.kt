package com.retro99.books.domain.model.links

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkRulesTest {

    @Test
    fun `parse and value round-trip for every source`() {
        CopySource.entries.forEach { source ->
            // Given
            val key = CopyKey(source, "id-1:with-colon")

            // When
            val parsed = CopyKey.parse(key.value)

            // Then
            assertEquals("${source.prefix}:id-1:with-colon", key.value)
            assertEquals(key, parsed, "for $source")
        }
    }

    @Test
    fun `parse returns null for bad input`() {
        // Given
        val inputs = listOf("", "library", "library:", ":id", "kindle:abc", "Library:abc")

        inputs.forEach { input ->
            // When
            val parsed = CopyKey.parse(input)

            // Then
            assertNull(parsed, "for '$input'")
        }
    }

    @Test
    fun `pairKey is order-independent`() {
        // Given
        val library = CopyKey(CopySource.Library, "b1")
        val storyteller = CopyKey(CopySource.Storyteller, "s1")

        // When
        val forward = pairKey(library, storyteller)
        val backward = pairKey(storyteller, library)

        // Then
        assertEquals("library:b1|storyteller:s1", forward)
        assertEquals(forward, backward)
    }

    @Test
    fun `mergeLinks joins members and keeps the first link id`() {
        // Given
        val first = link("link-1", "library:b1", "storyteller:s1")
        val second = link("link-2", "storyteller:s1", "audiobookshelf:a1")

        // When
        val merged = mergeLinks(first, second)

        // Then
        assertEquals(
            link("link-1", "library:b1", "storyteller:s1", "audiobookshelf:a1"),
            merged,
        )
    }

    @Test
    fun `mergeLinks returns null when the result would repeat a source`() {
        // Given
        val first = link("link-1", "library:b1", "storyteller:s1")
        val second = link("link-2", "storyteller:s2", "audiobookshelf:a1")

        // When
        val merged = mergeLinks(first, second)

        // Then
        assertNull(merged)
    }

    @Test
    fun `olderLinkId keeps the link created first and breaks ties on the id`() {
        // Given
        val cases = listOf(
            // The earlier instant wins, whatever the offset notation.
            Triple("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", "link-b"),
            Triple("2026-10-01T12:00:00Z", "2026-10-01T11:00:00+00:00", "link-a"),
            Triple("2026-10-01T11:00:00Z", "2026-10-01T11:00:00+00:00", "link-a"),
            Triple("2026-10-01T11:00:00Z", "not a date", "link-b"),
        )

        cases.forEach { (createdB, createdA, expected) ->
            // When
            val older = olderLinkId("link-b", createdB, "link-a", createdA)

            // Then
            assertEquals(expected, older, "for $createdB and $createdA")
        }
    }

    @Test
    fun `copyKey names a book by its source and id`() {
        // Given
        val cases = listOf(
            libraryBook("b1") to "library:b1",
            serverBook("s1", ServerType.Storyteller) to "storyteller:s1",
            serverBook("a1", ServerType.Audiobookshelf) to "audiobookshelf:a1",
        )

        cases.forEach { (book, expected) ->
            // When
            val key = book.copyKey()

            // Then
            assertEquals(expected, key.value)
        }
    }

    private fun link(linkId: String, vararg members: String) = BookLink(
        linkId = linkId,
        members = members.mapNotNull { member -> CopyKey.parse(member) }.toSet(),
    )

    private fun libraryBook(uuid: String) = BookDomainModel.LibraryBook(
        uuid = uuid,
        serverId = LOCAL_SERVER_ID,
        serverType = ServerType.Local,
        title = "Book",
        description = null,
        coverUrl = null,
        author = null,
        publicationDate = null,
        addedAt = "2026-10-01T00:00:00Z",
        lastOpenedAt = null,
        mediaResources = emptyList(),
    )

    private fun serverBook(uuid: String, serverType: ServerType) =
        BookDomainModel.StorytellerBook(
            uuid = uuid,
            serverId = "server-1",
            serverType = serverType,
            title = "Book",
            description = null,
            coverUrl = null,
            id = 0L,
            language = null,
            createdAt = null,
            updatedAt = null,
            publicationDate = null,
            rating = null,
            suffix = null,
            subtitle = null,
            ebookCoverUrl = null,
            audiobookCoverUrl = null,
            authors = emptyList(),
            narrators = emptyList(),
            creators = emptyList(),
            series = emptyList(),
            tags = emptyList(),
            collections = emptyList(),
            status = null,
            ebook = null,
            audiobook = null,
            readaloud = null,
        )
}
