package com.retro99.books.ui.list

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BooksListEmptyStateTest {
    @Test
    fun rendersHeaderWhenFilteredResultsAreEmptyAndHeaderIsAvailable() {
        assertTrue(
            shouldShowHeaderInEmptyBooksState(
                filteredBooksEmpty = true,
                isLoading = false,
                hasHeaderContent = true,
            ),
        )
    }

    @Test
    fun doesNotRenderHeaderWithoutHeaderContentOrWhileLoading() {
        assertFalse(
            shouldShowHeaderInEmptyBooksState(
                filteredBooksEmpty = true,
                isLoading = false,
                hasHeaderContent = false,
            ),
        )
        assertFalse(
            shouldShowHeaderInEmptyBooksState(
                filteredBooksEmpty = true,
                isLoading = true,
                hasHeaderContent = true,
            ),
        )
        assertFalse(
            shouldShowHeaderInEmptyBooksState(
                filteredBooksEmpty = false,
                isLoading = false,
                hasHeaderContent = true,
            ),
        )
    }
}
