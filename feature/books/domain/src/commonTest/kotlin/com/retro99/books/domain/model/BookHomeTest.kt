package com.retro99.books.domain.model

import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals

class BookHomeTest {

    @Test
    fun `home follows where the book lives`() {
        // Given
        val cases = listOf(
            libraryBook(RemoteFileAvailability.Available, localPath = null) to
                BookHome.ParrotCloud,
            libraryBook(RemoteFileAvailability.UploadPending) to BookHome.ParrotCloud,
            libraryBook(RemoteFileAvailability.Uploading) to BookHome.ParrotCloud,
            libraryBook(RemoteFileAvailability.Available) to BookHome.ParrotCloud,
            libraryBook(RemoteFileAvailability.None) to BookHome.ThisDevice,
            libraryBook(RemoteFileAvailability.UploadFailed) to BookHome.ThisDevice,
            libraryBook(RemoteFileAvailability.Deleting) to BookHome.ThisDevice,
            serverBook(ServerType.Storyteller) to BookHome.Storyteller,
            serverBook(ServerType.Audiobookshelf) to BookHome.Audiobookshelf,
        )

        cases.forEach { (book, expected) ->
            // When
            val home = book.home

            // Then
            assertEquals(expected, home, "for $book")
        }
    }

    private fun libraryBook(
        availability: RemoteFileAvailability,
        localPath: String? = "/library/book_ebook.epub",
    ) = BookDomainModel.LibraryBook(
        uuid = "book-1",
        serverId = "local",
        serverType = ServerType.Local,
        title = "Title",
        description = null,
        coverUrl = null,
        author = null,
        publicationDate = null,
        addedAt = "2026-10-01T00:00:00Z",
        lastOpenedAt = null,
        mediaResources = listOf(
            MediaResource(
                mediaType = BookType.EBOOK.value,
                localPath = localPath,
                remoteAvailability = availability,
            ),
        ),
    )

    private fun serverBook(serverType: ServerType) = BookDomainModel.StorytellerBook(
        uuid = "server-book",
        serverId = "server-1",
        serverType = serverType,
        title = "Title",
        description = null,
        coverUrl = null,
        id = 1,
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
