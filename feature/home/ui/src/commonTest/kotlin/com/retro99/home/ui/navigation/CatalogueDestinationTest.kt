package com.retro99.home.ui.navigation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan §10.6: a catalogue route names a catalogue and a short reference, never an address. */
class CatalogueDestinationTest {
    private val routes = listOf(
        HomeDestination.CatalogueSources,
        HomeDestination.CatalogueBrowse("3f2a9c1e-7b4d-4e2a-9c1e-5d6f7a8b9c0d"),
        HomeDestination.CatalogueBrowse("source", "r12"),
        HomeDestination.CataloguePublication("source", "r13"),
        HomeDestination.CatalogueDownloads,
    )

    @Test fun routes_round_trip_through_saved_state() {
        routes.forEach { route ->
            val encoded = Json.encodeToString(HomeDestination.serializer(), route)
            assertEquals(route, Json.decodeFromString(HomeDestination.serializer(), encoded))
        }
    }

    @Test fun a_route_is_small_and_holds_no_address() {
        routes.forEach { route ->
            val encoded = Json.encodeToString(HomeDestination.serializer(), route)
            assertTrue(encoded.length < 200, encoded)
            listOf("http", "://", "@", "?", "password", "apikey").forEach { assertFalse(it in encoded, encoded) }
        }
    }

    @Test fun an_address_or_account_details_cannot_be_put_in_a_route() {
        val bad = listOf(
            "https://books.home.lan/opds",
            "books.home.lan/opds",
            "//books.home.lan",
            "opds?apikey=abc",
            "reader:hunter2",
            "reader@books.home.lan",
            "data:image/png;base64,AAAA",
            "two words",
            "",
            "r".repeat(65),
        )
        bad.forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { HomeDestination.CatalogueBrowse("source", value) }
            assertFailsWith<IllegalArgumentException>(value) { HomeDestination.CatalogueBrowse(value) }
            assertFailsWith<IllegalArgumentException>(value) { HomeDestination.CataloguePublication("source", value) }
            assertFailsWith<IllegalArgumentException>(value) { HomeDestination.CataloguePublication(value, "r1") }
        }
        // Not through saved state either.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(
                HomeDestination.serializer(),
                """{"type":"com.retro99.home.ui.navigation.HomeDestination.CatalogueBrowse","sourceId":"source","targetRef":"https://books.home.lan/opds?apikey=abc"}""",
            )
        }
    }

    @Test fun the_message_of_a_refused_route_does_not_repeat_the_value() {
        val error = assertFailsWith<IllegalArgumentException> { HomeDestination.CatalogueBrowse("source", "https://books.home.lan/opds?apikey=SECRET") }
        assertFalse("SECRET" in error.message.orEmpty())
        assertFalse("books.home.lan" in error.message.orEmpty())
    }

    @Test fun analytics_names_are_fixed_words() {
        assertEquals(
            listOf("catalogue_sources", "catalogue_browse", "catalogue_browse", "catalogue_publication", "catalogue_downloads"),
            routes.map { it.analyticsScreenName() },
        )
    }

    @Test fun catalogue_screens_keep_the_tabs_and_hide_the_continue_bubble() {
        routes.forEach { route ->
            assertTrue(route.showBottomBar)
            assertTrue(route.hidesContinueBubble)
        }
    }
}
