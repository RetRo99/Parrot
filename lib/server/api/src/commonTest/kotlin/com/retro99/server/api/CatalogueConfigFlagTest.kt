package com.retro99.server.api

import kotlinx.serialization.json.Json
import kotlin.test.*

class CatalogueConfigFlagTest {
    @Test fun oldCatalogueDefaultsFalseAndPresetHintRoundTrips() {
        val old = Json.decodeFromString<ServerConfig>("""{"id":"old","name":"Name","type":"opds","baseUrl":"https://example.org/","addedAt":0}""")
        assertFalse(old.listEntriesAreBooks)
        val preset = old.copy(listEntriesAreBooks = true)
        assertEquals(preset, Json.decodeFromString<ServerConfig>(Json.encodeToString(preset)))
    }
}
