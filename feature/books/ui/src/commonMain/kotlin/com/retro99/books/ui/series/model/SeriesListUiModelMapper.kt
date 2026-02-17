package com.retro99.books.ui.series.model

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.SeriesDomainModel

fun SeriesDomainModel.toListUiModel(
    books: List<BookDomainModel.StorytellerBook>,
): SeriesListUiModel {
    // Find books that belong to this series
    val seriesBooks = books.filter { book ->
        book.series.any { it.uuid == uuid }
    }
    // Sort by position in series, then by title
    val sortedBooks = seriesBooks.sortedWith(
        compareBy(
            { book -> book.series.find { it.uuid == uuid }?.position ?: Double.MAX_VALUE },
            { it.title },
        ),
    )
    // Get cover from first book in series
    val coverUrl = sortedBooks.firstOrNull()?.coverUrl

    return SeriesListUiModel(
        uuid = uuid,
        name = name,
        featured = featured,
        coverUrl = coverUrl,
        bookCount = seriesBooks.size,
    )
}

