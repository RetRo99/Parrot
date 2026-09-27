package com.retro99.books.ui.list

import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookSortOption
import com.retro99.books.ui.model.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class BooksListAnalyticsValuesTest {

    @Test
    fun sortValueContainsSelectedOptionAndDirection() {
        assertEquals(
            "date_added_descending",
            BookSortConfig(BookSortOption.DATE_ADDED, SortDirection.DESCENDING).toAnalyticsValue(),
        )
        assertEquals("title_ascending", BookSortConfig().toAnalyticsValue())
    }

    @Test
    fun viewModeValueContainsSelectedLayout() {
        assertEquals("list", BookListViewMode.LIST.toAnalyticsValue())
        assertEquals("grid", BookListViewMode.GRID.toAnalyticsValue())
    }
}
