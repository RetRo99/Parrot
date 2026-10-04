package com.retro99.home.ui.navigation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotesHighlightsDestinationTest {
    @Test
    fun savedRoutesRoundTripWithAndWithoutBookKey() {
        listOf(HomeDestination.NotesHighlights(), HomeDestination.NotesHighlights("library:book"))
            .forEach { route ->
                val encoded = Json.encodeToString(HomeDestination.serializer(), route)
                assertEquals(route, Json.decodeFromString(HomeDestination.serializer(), encoded))
            }
    }

    @Test
    fun libraryScreenKeepsTabsAndHidesContinueBubble() {
        val route = HomeDestination.NotesHighlights()
        assertTrue(route.showBottomBar)
        assertTrue(route.hidesContinueBubble)
        assertEquals("notes_highlights", route.analyticsScreenName())
    }
}
