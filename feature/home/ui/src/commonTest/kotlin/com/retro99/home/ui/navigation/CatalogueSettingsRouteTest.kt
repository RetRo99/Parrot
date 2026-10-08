package com.retro99.home.ui.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

class CatalogueSettingsRouteTest {
    @Test fun settings_route_restores_only_an_id_and_an_account_editor_flag() {
        val route: HomeDestination = HomeDestination.CatalogueSettings("source-id", editAccount = true)
        val encoded = Json.encodeToString(route)
        assertEquals(route, Json.decodeFromString<HomeDestination>(encoded))
        assertFalse(encoded.contains("https://"))
        assertTrue(route.hidesContinueBubble)
        assertEquals("catalogue_settings", route.analyticsScreenName())
    }

    @Test fun settings_route_rejects_addresses_and_private_query_keys() {
        listOf("https://books.example/opds?key=secret", "source?token=secret", "").forEach {
            assertFailsWith<IllegalArgumentException> { HomeDestination.CatalogueSettings(it) }
        }
    }

    @Test fun browse_from_settings_starts_at_the_first_page() {
        assertNull(HomeDestination.CatalogueBrowse("source-id").targetRef)
    }
}
