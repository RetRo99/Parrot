package com.retro99.settings.ui.servers

import com.retro99.server.api.*
import kotlin.test.*

class CatalogueStatusMappingTest {
    @Test fun public_catalogue_ignores_signed_out_library_state_and_has_no_login_again_action() {
        val source = ServerConfig("source", "Books", ServerType.Opds, "https://books.example/opds/", 0)
        val mapped = mapCatalogueSource(source, CatalogueAccessStatus())
        assertEquals(ServerAccessState.Public, mapped.status.access)
        assertFalse(mapped.offersSignInAgain)
        assertEquals(CatalogueLastCheck(), mapped.status.lastCheck)
    }

    @Test fun disabled_source_keeps_last_check_but_exposes_turned_off_access() {
        val source = ServerConfig("source", "Books", ServerType.Opds, "https://books.example/opds/", 0, enabled = false)
        val checked = CatalogueAccessStatus(ServerAccessState.SignedIn("Patron"), CatalogueLastCheck(lastSuccessAt = 10))
        val mapped = mapCatalogueSource(source, checked)
        assertEquals(ServerAccessState.TurnedOff, mapped.status.access)
        assertEquals(10L, mapped.status.lastCheck.lastSuccessAt)
        assertFalse(mapped.offersSignInAgain)
    }
}
