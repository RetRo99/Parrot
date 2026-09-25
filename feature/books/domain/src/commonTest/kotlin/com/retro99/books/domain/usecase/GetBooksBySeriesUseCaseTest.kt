package com.retro99.books.domain.usecase

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.SeriesDomainModel
import kotlin.test.Test
import kotlin.test.assertEquals

class GetBooksBySeriesUseCaseTest {
    @Test
    fun includesLocalRepresentativesWithSourcePreservedSeriesAndSortsByPosition() {
        // Given
        val secondBook = localBook(
            uuid = "second",
            title = "Second book",
            series = listOf(series(name = "Shared series", position = 2.0)),
        )
        val firstBook = localBook(
            uuid = "first",
            title = "First book",
            series = listOf(series(name = "Shared series", position = 1.0)),
        )
        val unpositionedBook = localBook(
            uuid = "unpositioned",
            title = "Unpositioned book",
            series = listOf(series(name = "Shared series", position = null)),
        )
        val unrelatedBook = localBook(
            uuid = "unrelated",
            title = "Unrelated book",
            series = listOf(series(name = "Other series", position = 1.0)),
        )

        // When
        val result = booksInSeries(
            books = listOf(secondBook, unrelatedBook, unpositionedBook, firstBook),
            seriesName = "shared SERIES",
        )

        // Then
        assertEquals(
            listOf(firstBook, secondBook, unpositionedBook),
            result,
        )
    }
}

private fun localBook(
    uuid: String,
    title: String,
    series: List<SeriesDomainModel>,
): BookDomainModel.LocalBook = BookDomainModel.LocalBook(
    uuid = uuid,
    serverId = "local",
    serverType = null,
    title = title,
    description = null,
    coverUrl = null,
    author = null,
    filePath = "/$uuid.epub",
    fileSize = 1L,
    importedAt = "2026-09-25T00:00:00Z",
    lastOpenedAt = null,
    bookType = BookType.EBOOK,
    publicationDate = null,
    series = series,
)

private fun series(name: String, position: Double?): SeriesDomainModel = SeriesDomainModel(
    uuid = name,
    name = name,
    featured = null,
    position = position,
    createdAt = null,
    updatedAt = null,
)
