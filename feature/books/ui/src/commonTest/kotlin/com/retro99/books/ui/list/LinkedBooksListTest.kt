package com.retro99.books.ui.list

import com.retro99.base.server.ServerType
import com.retro99.books.domain.model.BookHome
import com.retro99.books.ui.model.BookFilterState
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.LinkedCopyUiModel
import com.retro99.books.ui.model.MediaResourceUiModel
import com.retro99.books.ui.model.isOnThisDevice
import com.retro99.books.ui.model.showDownloadedIcon
import com.retro99.books.ui.model.showHomeBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkedBooksListTest {

    private val storytellerCopy = copy(
        uuid = "s1",
        home = BookHome.Storyteller,
        isDownloaded = true,
        searchTerms = listOf("The Hobbit", "There and Back Again", "J. R. R. Tolkien"),
    )
    private val audiobookshelfCopy = copy(uuid = "a1", home = BookHome.Audiobookshelf)

    /** A linked book whose primary copy is in Parrot Cloud and not downloaded. */
    private val linked = parrotBook("b1", "Hobbit", linkedCopies = listOf(storytellerCopy))
    private val plain = parrotBook("b2", "Emma")

    @Test
    fun `homes lists the primary home first and the others in priority order`() {
        // Given
        val cases = listOf(
            plain to listOf(BookHome.ParrotCloud),
            linked to listOf(BookHome.ParrotCloud, BookHome.Storyteller),
            storytellerBook(
                linkedCopies = listOf(
                    audiobookshelfCopy,
                    copy(uuid = "b1", home = BookHome.ParrotCloud),
                    copy(uuid = "st-other", home = BookHome.Storyteller),
                ),
            ) to listOf(BookHome.Storyteller, BookHome.ParrotCloud, BookHome.Audiobookshelf),
        )

        cases.forEach { (book, expected) ->
            // When
            val homes = book.homes

            // Then
            assertEquals(expected, homes, "for ${book.title}")
        }
    }

    @Test
    fun `the badge shows when a linked book alone spans two homes`() {
        // When
        val linkedOnly = listOf(linked).showHomeBadge()
        val plainOnly = listOf(plain).showHomeBadge()

        // Then
        assertTrue(linkedOnly)
        assertFalse(plainOnly)
    }

    @Test
    fun `a source filter matches a linked book through any of its copies`() {
        // Given
        val cases = listOf(
            BookHome.Storyteller to listOf("b1"),
            BookHome.ParrotCloud to listOf("b2", "b1"),
            BookHome.Audiobookshelf to emptyList(),
            null to listOf("b2", "b1"),
        )

        cases.forEach { (home, expected) ->
            // When
            val state = state(filterState = BookFilterState(homeFilter = home))

            // Then
            assertEquals(expected, state.filteredBooks.map { book -> book.uuid }, "for $home")
        }
    }

    @Test
    fun `search matches a linked book when any copy matches`() {
        // Given
        val cases = listOf(
            "back again" to listOf("b1"),
            "tolkien" to listOf("b1"),
            "hobbit" to listOf("b1"),
            "emma" to listOf("b2"),
            "dune" to emptyList(),
        )

        cases.forEach { (query, expected) ->
            // When
            val state = state(searchQuery = query)

            // Then
            assertEquals(expected, state.filteredBooks.map { book -> book.uuid }, "for $query")
        }
    }

    @Test
    fun `search matches a subtitle`() {
        // Given
        val book = storytellerBook(subtitle = "There and Back Again")

        // When
        val state = BooksListViewState(books = listOf(book), searchQuery = "back again")

        // Then
        assertEquals(listOf(book), state.filteredBooks)
    }

    @Test
    fun `a linked book counts as downloaded when any copy is`() {
        // When
        val state = state(
            filterState = BookFilterState(activeQuickFilters = setOf(BookQuickFilter.CACHED)),
        )

        // Then
        assertEquals(listOf("b1"), state.filteredBooks.map { book -> book.uuid })
        assertTrue(linked.isOnThisDevice(progressInfo = null))
        assertTrue(linked.showDownloadedIcon(progressInfo = null))
        assertFalse(plain.showDownloadedIcon(progressInfo = null))
    }

    @Test
    fun `the source filter offers every home in the list`() {
        // When
        val homes = state().availableHomes

        // Then
        assertEquals(listOf(BookHome.ParrotCloud, BookHome.Storyteller), homes)
    }

    private fun state(
        searchQuery: String = "",
        filterState: BookFilterState = BookFilterState(),
    ) = BooksListViewState(
        books = listOf(linked, plain),
        searchQuery = searchQuery,
        filterState = filterState,
    )

    private fun copy(
        uuid: String,
        home: BookHome,
        isDownloaded: Boolean = false,
        searchTerms: List<String> = emptyList(),
    ) = LinkedCopyUiModel(
        key = "copy:$uuid",
        serverId = "server",
        uuid = uuid,
        title = "Copy $uuid",
        home = home,
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        isDownloaded = isDownloaded,
        searchTerms = searchTerms,
    )

    private fun parrotBook(
        uuid: String,
        title: String,
        linkedCopies: List<LinkedCopyUiModel> = emptyList(),
    ) = BookUiModel.LibraryBook(
        uuid = uuid,
        serverId = "local",
        serverType = ServerType.Local,
        title = title,
        description = null,
        coverUrl = null,
        author = null,
        publicationDate = null,
        addedAt = "2026-10-01T00:00:00Z",
        lastOpenedAt = null,
        home = BookHome.ParrotCloud,
        mediaResources = listOf(
            MediaResourceUiModel(
                mediaType = "ebook",
                localPath = null,
                remoteAvailability = "Available",
                size = 1,
                contentHash = null,
                contentHashAlgorithm = null,
            ),
        ),
        linkedCopies = linkedCopies,
    )

    private fun storytellerBook(
        subtitle: String? = null,
        linkedCopies: List<LinkedCopyUiModel> = emptyList(),
    ) = BookUiModel.StorytellerBook(
        uuid = "s9",
        serverId = "server",
        serverType = ServerType.Storyteller,
        title = "Server book",
        description = null,
        coverUrl = null,
        subtitle = subtitle,
        authors = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        statusName = null,
        rating = null,
        publicationDate = null,
        dateAdded = null,
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        ebookFilepath = null,
        audiobookFilepath = null,
        readaloudFilepath = null,
        home = BookHome.Storyteller,
        linkedCopies = linkedCopies,
    )
}
