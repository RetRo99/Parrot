package com.retro99.books.domain.model

import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServerBookMapperTest {

    @Test
    fun `toBookDomainModel keeps the language and identifiers of a server book`() {
        // Given
        val serverBook = serverBook(isLocal = false, serverType = ServerType.Audiobookshelf)

        // When
        val book = assertIs<BookDomainModel.StorytellerBook>(serverBook.toBookDomainModel())

        // Then
        assertEquals("en", book.language)
        assertEquals("9780261102217", book.isbn)
        assertEquals("B007978NPG", book.asin)
    }

    @Test
    fun `toBookDomainModel keeps the isbn of a library book`() {
        // Given
        val serverBook = serverBook(isLocal = true, serverType = ServerType.Local)

        // When
        val book = assertIs<BookDomainModel.LibraryBook>(serverBook.toBookDomainModel())

        // Then
        assertEquals("9780261102217", book.isbn)
    }

    private fun serverBook(isLocal: Boolean, serverType: ServerType) = ServerBook(
        uuid = "book-1",
        serverId = "server-1",
        title = "The Hobbit",
        description = null,
        coverUrl = null,
        authors = listOf("J. R. R. Tolkien"),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        isLocal = isLocal,
        serverType = serverType,
        language = "en",
        isbn = "9780261102217",
        asin = "B007978NPG",
    )
}
