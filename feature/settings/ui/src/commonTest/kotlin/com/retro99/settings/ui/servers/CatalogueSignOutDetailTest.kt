package com.retro99.settings.ui.servers

import com.retro99.server.api.*
import kotlin.test.*

class CatalogueSignOutDetailTest {
    @Test fun sign_out_detail_is_only_shown_when_the_profile_has_a_catalogue() {
        assertFalse(ServerManagementViewState().hasCatalogueSignOutDetail)
        val catalogue = ServerConfig("cat", "Books", ServerType.Opds, "https://books.example/opds", 0)
        assertTrue(ServerManagementViewState(catalogueSources = listOf(mapCatalogueSource(catalogue, CatalogueAccessStatus()))).hasCatalogueSignOutDetail)
        assertTrue(ServerManagementViewState(catalogueSources = listOf(mapCatalogueSource(catalogue.copy(enabled = false), CatalogueAccessStatus()))).hasCatalogueSignOutDetail)
    }
}
