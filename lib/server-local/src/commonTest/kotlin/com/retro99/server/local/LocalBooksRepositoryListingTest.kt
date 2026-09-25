package com.retro99.server.local

import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.source.ImportedBooksLocalSource
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.api.library.LocalContentIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

class LocalBooksRepositoryListingTest {
    @Test
    fun importedBookInventoryIsAdvertisedAsComplete() = runBlocking {
        // Given
        val source = FakeImportedBooksLocalSource(
            listOf(localBook("book-a"), localBook("book-b")),
        )
        val classUnderTest = LocalBooksRepository(source, "local-connection")

        // When
        val listing = classUnderTest.getLibraryListing().first().getOrElse {
            error("Local listing unexpectedly failed")
        }

        // Then
        assertEquals(ServerBookListingCompleteness.Complete, listing.completeness)
        assertEquals(listOf("book-a", "book-b"), listing.books.map { book -> book.uuid })
    }

    @Test
    fun missingHashAlgorithmIsNotAssumedToBeSha256() = runBlocking {
        // Given
        val source = FakeImportedBooksLocalSource(
            listOf(
                localBook(
                    uuid = "book-a",
                    contentHash = VALID_HASH,
                    contentHashAlgorithm = null,
                ),
            ),
        )
        val classUnderTest = LocalBooksRepository(source, "local-connection")

        // When
        val book = classUnderTest.getLibraryListing().first().getOrElse {
            error("Local listing unexpectedly failed")
        }.books.single()

        // Then
        assertEquals(null, book.contentHashAlgorithm)
        assertEquals(null, book.libraryBookId)
        assertEquals(null, book.mediaResources.single().contentHashAlgorithm)
    }

    private fun localBook(
        uuid: String,
        contentHash: String? = null,
        contentHashAlgorithm: String? = null,
    ) = BookDomainModel.LocalBook(
        uuid = uuid,
        serverId = "local-connection",
        serverType = null,
        title = "Title $uuid",
        description = null,
        coverUrl = null,
        author = null,
        filePath = "/imports/$uuid.epub",
        fileSize = 1L,
        contentHash = contentHash,
        contentHashAlgorithm = contentHashAlgorithm,
        importedAt = "2026-09-25T00:00:00Z",
        lastOpenedAt = null,
        bookType = BookType.EBOOK,
        publicationDate = null,
    )

    private companion object {
        const val VALID_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

private class FakeImportedBooksLocalSource(
    private val books: List<BookDomainModel.LocalBook>,
) : ImportedBooksLocalSource {
    override suspend fun saveImportedBook(book: BookDomainModel.LocalBook): CompletableResult =
        error("This method is unused in the test")

    override fun observeAllImportedBooks(): Flow<List<BookDomainModel.LocalBook>> = flowOf(books)

    override suspend fun getImportedBookByUuid(uuid: String): BookDomainModel.LocalBook? =
        error("This method is unused in the test")

    override suspend fun getImportedBookByContentHash(
        contentHash: String,
    ): BookDomainModel.LocalBook? = error("This method is unused in the test")

    override suspend fun deleteImportedBook(uuid: String): CompletableResult =
        error("This method is unused in the test")

    override suspend fun updateLastOpenedAt(uuid: String): CompletableResult =
        error("This method is unused in the test")
}
