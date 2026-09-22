package com.retro99.server.parrotcloud

import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.sync.domain.LegacySyncTransport
import com.retro99.sync.domain.SyncChange
import com.retro99.sync.domain.SyncChangePage
import com.retro99.sync.domain.SyncMutationRequest
import com.retro99.sync.domain.SyncMutationResponse
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Supabase RPC mapping for legacy library mutations.
 *
 * This adapter performs no outbox or local-database work. It only translates
 * the shared request/page models to and from the RPC wire format.
 */
@Single(binds = [LegacySyncTransport::class])
class ParrotCloudLegacySyncTransport(
    @Provided private val clientProvider: SupabaseClientProvider,
) : LegacySyncTransport {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun push(
        mutations: List<SyncMutationRequest>,
        cursor: String?,
    ): List<SyncMutationResponse> {
        if (mutations.isEmpty()) return emptyList()

        val response = clientProvider.client.postgrest
            .rpc(
                "push_sync_changes",
                buildJsonObject {
                    put(
                        "mutations",
                        json.encodeToJsonElement(mutations.map { it.toRpcRequest() }),
                    )
                    put("client_cursor", cursor?.toLongOrNull() ?: 0L)
                },
            )
            .decodeAs<List<ParrotCloudLegacyMutationResponse>>()

        return response.map { result ->
            SyncMutationResponse(
                mutationId = result.mutationId,
                status = result.status,
                cloudBookId = result.cloudBookId,
                revision = result.revision,
                payload = result.payload?.let(json::encodeToString),
                reason = result.reason,
            )
        }
    }

    override suspend fun pull(
        cursor: String?,
        limit: Int,
    ): SyncChangePage {
        val response = clientProvider.client.postgrest
            .rpc(
                "pull_sync_changes",
                buildJsonObject {
                    put("cursor", cursor?.toLongOrNull() ?: 0L)
                    put("limit", limit.coerceIn(1, MAX_BATCH_SIZE))
                },
            )
            .decodeAs<ParrotCloudLegacyPullResponse>()

        return SyncChangePage(
            changes = response.changes.map { change ->
                SyncChange(
                    entityType = change.entityType,
                    payload = json.encodeToString(change.payload),
                    revision = change.revision,
                )
            },
            nextCursor = response.nextCursor.toString(),
            hasMore = response.hasMore,
        )
    }

    private fun SyncMutationRequest.toRpcRequest() = ParrotCloudLegacyMutationRequest(
        mutationId = mutationId,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = json.parseToJsonElement(payload),
        baseRevision = baseRevision,
        createdAt = createdAt,
    )

    private companion object {
        const val MAX_BATCH_SIZE = 50
    }
}

@Serializable
private data class ParrotCloudLegacyMutationRequest(
    @SerialName("mutation_id")
    val mutationId: String,
    @SerialName("entity_type")
    val entityType: String,
    @SerialName("entity_id")
    val entityId: String,
    val operation: String,
    val payload: JsonElement,
    @SerialName("base_revision")
    val baseRevision: Long?,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
private data class ParrotCloudLegacyMutationResponse(
    @SerialName("mutation_id")
    val mutationId: String,
    val status: String,
    @SerialName("cloud_book_id")
    val cloudBookId: String? = null,
    val revision: Long? = null,
    val payload: JsonElement? = null,
    val reason: String? = null,
)

@Serializable
private data class ParrotCloudLegacyPullResponse(
    val changes: List<ParrotCloudLegacyRemoteChange>,
    @SerialName("next_cursor")
    val nextCursor: Long,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

@Serializable
private data class ParrotCloudLegacyRemoteChange(
    @SerialName("entity_type")
    val entityType: String,
    val payload: JsonElement,
    val revision: Long,
)
