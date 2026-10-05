package com.retro99.books.ui.series.model

import com.retro99.books.domain.model.SeriesBrowseEntry
import com.retro99.books.ui.model.toUiModel

fun SeriesBrowseEntry.toListUiModel(): SeriesListUiModel {
    val authors = books.map { it.book.toUiModel().authors }
    val common = authors.firstOrNull()?.singleOrNull()?.takeIf { name ->
        authors.all { it.size == 1 && it.single() == name } && !name.equals("Unknown author", true)
    }
    return SeriesListUiModel(key, name, null, books.firstOrNull()?.book?.coverUrl,
        books.size, finishedCount, common, progress)
}
