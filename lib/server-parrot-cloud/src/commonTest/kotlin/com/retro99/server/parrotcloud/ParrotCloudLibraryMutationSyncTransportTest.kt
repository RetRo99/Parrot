package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ParrotCloudLibraryMutationSyncTransportTest {
    @Test
    fun routesDeleteToDedicatedRpcAndMapsTheResponse() = runTest {
        // Given
        val deletedAt = "2026-09-25T08:30:00Z"
        val rpc = RecordingParrotCloudLibraryMutationRpc(
            responses = mapOf(
                "delete_cloud_books" to Json.parseToJsonElement(
                    """
                    [{
                      "mutation_id":"delete-1",
                      "status":"accepted",
                      "cloud_book_id":"cloud-book-1",
                      "revision":23,
                      "payload":{
                        "library_book_id":"sha-256-v1:hash",
                        "cloud_book_id":"cloud-book-1",
                        "content_hash":"hash",
                        "content_hash_algorithm":"sha-256-v1",
                        "title":"Book",
                        "format":"ebook",
                        "remote_revision":23,
                        "deleted_at":"$deletedAt"
                      },
                      "reason":null,
                      "retry_after_ms":null
                    }]
                    """.trimIndent(),
                ),
            ),
        )
        val transport = ParrotCloudLibraryMutationSyncTransport(rpc)

        // When
        val responses = transport.push(
            mutations = listOf(
                mutation(
                    mutationId = "delete-1",
                    operation = SyncOutboxEntry.OPERATION_DELETE,
                    baseRevision = 22L,
                ),
            ),
            cursor = "17",
        )

        // Then
        val call = rpc.calls.single()
        assertEquals("delete_cloud_books", call.function)
        assertEquals(17L, call.arguments["client_cursor"]?.jsonPrimitive?.content?.toLong())
        val wireMutation = call.arguments["mutations"]?.jsonArray?.single()?.jsonObject
        assertNotNull(wireMutation)
        assertEquals("delete-1", wireMutation["mutation_id"]?.jsonPrimitive?.content)
        assertEquals("library_book", wireMutation["entity_type"]?.jsonPrimitive?.content)
        assertEquals("delete", wireMutation["operation"]?.jsonPrimitive?.content)
        assertEquals(22L, wireMutation["base_revision"]?.jsonPrimitive?.content?.toLong())
        val response = responses.single()
        assertEquals("delete-1", response.mutationId)
        assertEquals("accepted", response.status)
        assertEquals("cloud-book-1", response.cloudBookId)
        assertEquals(23L, response.revision)
        assertEquals("$deletedAt", Json.parseToJsonElement(response.payload!!)
            .jsonObject["deleted_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun rebasesDeleteOnAnAcceptedUpsertFromTheSamePushBatch() = runTest {
        // Given
        val rpc = RecordingParrotCloudLibraryMutationRpc(
            responses = mapOf(
                "push_sync_changes" to Json.parseToJsonElement(
                    """
                    [{
                      "mutation_id":"upsert-1",
                      "status":"accepted",
                      "cloud_book_id":"cloud-book-1",
                      "revision":41
                    }]
                    """.trimIndent(),
                ),
                "delete_cloud_books" to Json.parseToJsonElement(
                    """
                    [{
                      "mutation_id":"delete-1",
                      "status":"accepted",
                      "cloud_book_id":"cloud-book-1",
                      "revision":42
                    }]
                    """.trimIndent(),
                ),
            ),
        )
        val transport = ParrotCloudLibraryMutationSyncTransport(rpc)

        // When
        val responses = transport.push(
            mutations = listOf(
                mutation(
                    mutationId = "upsert-1",
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    baseRevision = 39L,
                ),
                mutation(
                    mutationId = "delete-1",
                    operation = SyncOutboxEntry.OPERATION_DELETE,
                    baseRevision = 39L,
                ),
            ),
            cursor = "4",
        )

        // Then
        assertEquals(listOf("push_sync_changes", "delete_cloud_books"), rpc.calls.map { call ->
            call.function
        })
        val deleteMutation = rpc.calls[1].arguments["mutations"]?.jsonArray?.single()?.jsonObject
        assertEquals(
            41L,
            deleteMutation?.get("base_revision")?.jsonPrimitive?.content?.toLong(),
        )
        assertEquals(listOf("upsert-1", "delete-1"), responses.map { response ->
            response.mutationId
        })
        assertEquals(42L, responses.last().revision)
    }

    @Test
    fun mapsConflictReasonPayloadAndRetryDelay() = runTest {
        // Given
        val rpc = RecordingParrotCloudLibraryMutationRpc(
            responses = mapOf(
                "delete_cloud_books" to Json.parseToJsonElement(
                    """
                    [{
                      "mutation_id":"delete-1",
                      "status":"conflict",
                      "revision":24,
                      "payload":{"deleted_at":"2026-09-25T08:30:00Z"},
                      "reason":"stale_revision",
                      "retry_after_ms":750
                    }]
                    """.trimIndent(),
                ),
            ),
        )
        val transport = ParrotCloudLibraryMutationSyncTransport(rpc)

        // When
        val response = transport.push(
            mutations = listOf(
                mutation(
                    mutationId = "delete-1",
                    operation = SyncOutboxEntry.OPERATION_DELETE,
                    baseRevision = 22L,
                ),
            ),
            cursor = "0",
        ).single()

        // Then
        assertEquals("conflict", response.status)
        assertEquals(24L, response.revision)
        assertEquals("stale_revision", response.reason)
        assertEquals(750L, response.retryAfterMillis)
        assertEquals(
            "2026-09-25T08:30:00Z",
            Json.parseToJsonElement(response.payload!!)
                .jsonObject["deleted_at"]?.jsonPrimitive?.content,
        )
    }

    private fun mutation(
        mutationId: String,
        operation: String,
        baseRevision: Long?,
    ) = SyncMutationRequest(
        mutationId = mutationId,
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
        entityId = "sha-256-v1:hash",
        operation = operation,
        payload = """{"library_book_id":"sha-256-v1:hash","title":"Book"}""",
        baseRevision = baseRevision,
        createdAt = "2026-09-25T08:00:00Z",
    )
}

private data class RecordedRpcCall(
    val function: String,
    val arguments: JsonObject,
)

private class RecordingParrotCloudLibraryMutationRpc(
    private val responses: Map<String, JsonElement>,
) : ParrotCloudLibraryMutationRpc {
    val calls = mutableListOf<RecordedRpcCall>()

    override suspend fun invoke(function: String, arguments: JsonObject): JsonElement {
        calls += RecordedRpcCall(function, arguments)
        return responses[function] ?: JsonArray(emptyList())
    }
}
