package com.retro99.books.ui.model

import com.retro99.base.server.ServerType
import com.retro99.books.domain.model.BookHome
import kotlin.test.Test
import kotlin.test.assertEquals

class BookHomeUiTest {

    @Test
    fun `the badge shows only when the list has more than one home`() {
        // Given
        val cases = listOf(
            listOf(library(BookHome.ThisDevice), library(BookHome.ThisDevice)) to false,
            listOf(library(BookHome.ThisDevice), library(BookHome.ParrotCloud)) to true,
            listOf(library(BookHome.ParrotCloud), server(ServerType.Storyteller)) to true,
            listOf(server(ServerType.Storyteller), server(ServerType.Storyteller)) to false,
            emptyList<BookUiModel>() to false,
        )

        cases.forEach { (books, expected) ->
            // When
            val shown = books.showHomeBadge()

            // Then
            assertEquals(expected, shown, "for ${books.map { book -> book.home }}")
        }
    }

    @Test
    fun `the downloaded icon follows the card rule`() {
        // Given
        val cached = progress(cached = true)
        val cases = listOf(
            Triple(library(BookHome.ParrotCloud, onDevice = true), null, true),
            Triple(library(BookHome.ParrotCloud, onDevice = false), null, false),
            Triple(library(BookHome.ThisDevice, onDevice = true), cached, false),
            Triple(server(ServerType.Storyteller), cached, true),
            Triple(server(ServerType.Storyteller), progress(cached = false), false),
            Triple(server(ServerType.Storyteller), null, false),
        )

        cases.forEach { (book, progressInfo, expected) ->
            // When
            val shown = book.showDownloadedIcon(progressInfo)

            // Then
            assertEquals(expected, shown, "for $book")
        }
    }

    @Test
    fun `the Downloaded quick filter keeps books with a device copy or a cached file`() {
        // Given
        val cases = listOf(
            Triple(library(BookHome.ThisDevice, onDevice = true), null, true),
            Triple(library(BookHome.ParrotCloud, onDevice = true), null, true),
            Triple(library(BookHome.ParrotCloud, onDevice = false), null, false),
            Triple(server(ServerType.Storyteller), progress(cached = true), true),
            Triple(server(ServerType.Storyteller), null, false),
        )

        cases.forEach { (book, progressInfo, expected) ->
            // When
            val kept = book.isOnThisDevice(progressInfo)

            // Then
            assertEquals(expected, kept, "for $book")
        }
    }

    @Test
    fun `the source filter keeps books of the chosen home`() {
        // Given
        val device = library(BookHome.ThisDevice)
        val parrot = library(BookHome.ParrotCloud)
        val storyteller = server(ServerType.Storyteller)
        val audiobookshelf = server(ServerType.Audiobookshelf)
        val books = listOf(device, parrot, storyteller, audiobookshelf)
        val cases = listOf(
            null to books,
            BookHome.ThisDevice to listOf(device),
            BookHome.ParrotCloud to listOf(parrot),
            BookHome.Storyteller to listOf(storyteller),
            BookHome.Audiobookshelf to listOf(audiobookshelf),
        )

        cases.forEach { (home, expected) ->
            // When
            val filtered = books.filterByHome(home)

            // Then
            assertEquals(expected, filtered, "for $home")
        }
    }

    private fun library(home: BookHome, onDevice: Boolean = true) = BookUiModel.LibraryBook(
        uuid = "library-$home-$onDevice",
        serverId = "local",
        serverType = ServerType.Local,
        title = "Book",
        description = null,
        coverUrl = null,
        author = null,
        publicationDate = null,
        addedAt = "2026-10-01T00:00:00Z",
        lastOpenedAt = null,
        home = home,
        mediaResources = listOf(
            MediaResourceUiModel(
                mediaType = "ebook",
                localPath = if (onDevice) "/library/book.epub" else null,
                remoteAvailability = if (home == BookHome.ParrotCloud) "Available" else "None",
                size = 1,
                contentHash = null,
                contentHashAlgorithm = null,
            ),
        ),
    )

    private fun server(serverType: ServerType) = BookUiModel.StorytellerBook(
        uuid = "server-$serverType",
        serverId = "server",
        serverType = serverType,
        title = "Book",
        description = null,
        coverUrl = null,
        subtitle = null,
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
        home = if (serverType == ServerType.Audiobookshelf) {
            BookHome.Audiobookshelf
        } else {
            BookHome.Storyteller
        },
    )

    private fun progress(cached: Boolean) = BookProgressInfoUiModel(
        bookUuid = "server",
        localProgression = null,
        remoteProgression = null,
        hasAnyCached = cached,
    )
}
