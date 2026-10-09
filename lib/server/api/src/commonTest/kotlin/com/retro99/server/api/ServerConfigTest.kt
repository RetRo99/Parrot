package com.retro99.server.api

import kotlinx.serialization.json.Json
import kotlin.test.*

class ServerConfigTest {
    @Test fun old_config_defaults_to_enabled_and_disabled_round_trips() {
        val json = Json
        val config = json.decodeFromString<ServerConfig>("""{"id":"old","name":"Old","type":"storyteller","baseUrl":"https://example.org","addedAt":0}""")
        assertTrue(config.enabled)
        val disabled = json.decodeFromString<ServerConfig>(json.encodeToString(config).dropLast(1) + ",\"enabled\":false}")
        assertTrue(json.encodeToString(disabled).contains("\"enabled\":false"))
        assertFalse(disabled.enabled)
        assertEquals(config, json.decodeFromString(json.encodeToString(config)))
    }
}
