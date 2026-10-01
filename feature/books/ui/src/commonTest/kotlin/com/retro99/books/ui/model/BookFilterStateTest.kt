package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookHome
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookFilterStateTest {

    // The same settings as the preferences Json that stores the list settings.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `a filter saved before homes existed decodes to no source filter`() {
        // Given
        val saved = """{"activeQuickFilters":["FAVORITES"],"serverTypeFilter":"Local"}"""

        // When
        val state = json.decodeFromString<BookFilterState>(saved)

        // Then
        assertNull(state.homeFilter)
        assertEquals(setOf(BookQuickFilter.FAVORITES), state.activeQuickFilters)
    }

    @Test
    fun `a home filter round trips`() {
        // Given
        val state = BookFilterState(homeFilter = BookHome.ParrotCloud)

        // When
        val decoded = json.decodeFromString<BookFilterState>(json.encodeToString(state))

        // Then
        assertEquals(state, decoded)
    }
}
