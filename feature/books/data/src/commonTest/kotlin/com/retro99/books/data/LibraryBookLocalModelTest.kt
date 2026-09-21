package com.retro99.books.data

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.ServerType
import com.retro99.books.data.model.toLibraryBookLocalModel
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryBookLocalModelTest {

    @Test
    fun mapsContentHashToLibraryBookId() {
        val book = localBook(contentHash = "hash-1", contentHashAlgorithm = "sha256")

        val libraryBook = book.toLibraryBookLocalModel()

        assertEquals("hash-1", libraryBook?.libraryBookId)
        assertEquals("hash-1", libraryBook?.contentHash)
        assertEquals("sha256", libraryBook?.contentHashAlgorithm)
        assertEquals("A book", libraryBook?.title)
        assertEquals("An author", libraryBook?.author)
        assertEquals("ebook", libraryBook?.format)
    }

    @Test
    fun skipsLibraryMappingWithoutContentHash() {
        val book = localBook(contentHash = null, contentHashAlgorithm = null)

        assertNull(book.toLibraryBookLocalModel())
    }

    private fun localBook(
        contentHash: String?,
        contentHashAlgorithm: String?,
    ) = BookDomainModel.LocalBook(
        uuid = "uuid-1",
        serverId = LOCAL_SERVER_ID,
        serverType = ServerType.Local,
        title = "A book",
        description = null,
        coverUrl = null,
        author = "An author",
        filePath = "/books/book.epub",
        fileSize = 100L,
        contentHash = contentHash,
        contentHashAlgorithm = contentHashAlgorithm,
        importedAt = "2026-09-18T10:00:00Z",
        lastOpenedAt = null,
        bookType = BookType.EBOOK,
        publicationDate = null,
    )
}
