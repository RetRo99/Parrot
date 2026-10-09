package com.retro99.server.implementation

import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a preset supplies (its search address, "list entries are books") belongs to the preset's own server. */
class CataloguePresetHintsTest {
    private val search = "https://books.example/search{?query}"

    private suspend fun presetRegistry(preferences: RegistryPreferences = RegistryPreferences()): Pair<ServerRegistryImpl, ServerConfig> {
        val registry = registryWithOwnStores(preferences, RegistryUser("a"))
        val added = registry.addServerWithId("source", "Books", ServerType.Opds, "https://books.example/opds/")
        registry.updateServer(added.copy(listEntriesAreBooks = true, searchTemplate = search))
        return registry to checkNotNull(registry.getServer("source"))
    }

    @Test fun a_path_or_query_only_edit_keeps_the_presets_search_address_and_list_hint() = runTest {
        val (registry, preset) = presetRegistry()
        assertTrue(preset.listEntriesAreBooks)
        assertEquals(search, preset.searchTemplate)

        for (address in listOf("https://books.example/other/", "https://books.example/other/?library=2", "https://BOOKS.example:443/opds/")) {
            registry.updateServer(checkNotNull(registry.getServer("source")).copy(baseUrl = address))
            val stored = checkNotNull(registry.getServer("source"))
            assertEquals(address, stored.baseUrl)
            assertTrue(stored.listEntriesAreBooks, address)
            assertEquals(search, stored.searchTemplate, address)
        }
    }

    @Test fun an_edit_to_another_host_scheme_or_port_clears_both_and_stays_cleared_after_a_restart() = runTest {
        for (address in listOf("https://other.example/opds/", "https://books.example:8443/opds/", "http://books.example/opds/", "not an address")) {
            val preferences = RegistryPreferences()
            val (registry, preset) = presetRegistry(preferences)

            registry.updateServer(preset.copy(baseUrl = address))

            for (stored in listOf(registry.getServer("source"), registryWithOwnStores(preferences, RegistryUser("a")).getServer("source"))) {
                assertEquals(address, stored?.baseUrl)
                assertFalse(checkNotNull(stored).listEntriesAreBooks, address)
                assertNull(stored.searchTemplate, address)
            }
        }
    }

    @Test fun moving_back_to_the_presets_host_does_not_bring_the_hints_back() = runTest {
        val (registry, preset) = presetRegistry()
        registry.updateServer(preset.copy(baseUrl = "https://other.example/opds/"))

        registry.updateServer(checkNotNull(registry.getServer("source")).copy(baseUrl = preset.baseUrl))

        val stored = checkNotNull(registry.getServer("source"))
        assertFalse(stored.listEntriesAreBooks)
        assertNull(stored.searchTemplate)
    }

    @Test fun renaming_or_turning_off_a_preset_changes_neither() = runTest {
        val (registry, preset) = presetRegistry()

        registry.updateServer(preset.copy(name = "My books", enabled = false))

        val stored = checkNotNull(registry.getServer("source"))
        assertTrue(stored.listEntriesAreBooks)
        assertEquals(search, stored.searchTemplate)
    }
}
