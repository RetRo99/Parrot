package com.retro99.books.ui.list

import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookSortConfig

internal fun BookSortConfig.toAnalyticsValue(): String =
    "${option.name.lowercase()}_${direction.name.lowercase()}"

internal fun BookListViewMode.toAnalyticsValue(): String = name.lowercase()
