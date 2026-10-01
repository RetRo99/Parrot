package com.retro99.books.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDockPaddingTest {

    @Test
    fun `the dock sits 8dp above a shown keyboard`() {
        // Given
        val imeBottomPx = 840

        // When
        val padding = searchDockBottomPadding(imeBottomPx)

        // Then
        assertEquals(8.dp, padding)
    }

    @Test
    fun `the dock sits 20dp above the screen edge without a keyboard`() {
        // Given
        val imeBottomPx = 0

        // When
        val padding = searchDockBottomPadding(imeBottomPx)

        // Then
        assertEquals(20.dp, padding)
    }
}
