package com.retro99.sync.data

import com.retro99.server.api.ServerPosition
import com.retro99.sync.data.model.SyncMutationRequest
import com.retro99.sync.data.model.SyncMutationResponse
import com.retro99.sync.data.model.syncJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SyncRemoteModelsTest {
    @Test
    fun mutationRequestUsesBackendFieldNames() {
        val request = SyncMutationRequest(
            mutationId = "mutation-1",
            entityType = "reading_position",
            entityId = "book-1",
            operation = "upsert",
            payload = buildJsonObject { put("contentHash", "hash-1") },
            baseRevision = null,
            createdAt = "2026-09-21T00:00:00Z",
        )

        val json = syncJson.encodeToJsonElement(request).toString()

        assertEquals(true, json.contains("\"mutation_id\""))
        assertEquals(true, json.contains("\"entity_type\""))
        assertEquals(true, json.contains("\"base_revision\""))
    }

    @Test
    fun serverPositionPayloadRoundTrips() {
        val payload = com.retro99.sync.data.model.SyncReadingPositionPayload(
            bookUuid = "book-1",
            contentHash = "hash-1",
            contentHashAlgorithm = "sha256",
            position = ServerPosition(
                bookUuid = "book-1",
                serverId = "local",
                timestamp = 1L,
                createdAt = null,
                updatedAt = null,
                locatorHref = "chapter.xhtml",
                locatorType = null,
                locatorTitle = null,
                locatorTarget = null,
                audioTimestampMs = null,
                chapterIndex = 1,
                progression = 0.5,
                totalChapters = 2,
                totalDurationMs = null,
                totalProgression = 0.25,
                position = 10,
            ),
        )

        val decoded = syncJson.decodeFromString<com.retro99.sync.data.model.SyncReadingPositionPayload>(
            syncJson.encodeToString(payload),
        )

        assertEquals(payload, decoded)
    }

    @Test
    fun conflictResponseIncludesAuthoritativePayload() {
        val response = syncJson.decodeFromString<SyncMutationResponse>(
            """
            {
                "mutation_id": "mutation-1",
                "status": "conflict",
                "entity_type": "reading_position",
                "entity_id": "sha256:hash-1",
                "change_id": 7,
                "revision": 3,
                "payload": {"contentHash": "hash-1"}
            }
            """.trimIndent(),
        )

        assertEquals("conflict", response.status)
        assertEquals(7L, response.changeId)
        assertEquals(3L, response.revision)
        assertNotNull(response.payload)
    }
}
