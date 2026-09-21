package com.retro99.sync.data.model

import com.retro99.server.api.ServerPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal val syncJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
internal data class SyncMutationRequest(
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
internal data class SyncMutationResponse(
    @SerialName("mutation_id")
    val mutationId: String,
    val status: String,
    @SerialName("entity_type")
    val entityType: String? = null,
    @SerialName("entity_id")
    val entityId: String? = null,
    val revision: Long? = null,
    @SerialName("change_id")
    val changeId: Long? = null,
    val payload: JsonElement? = null,
    val reason: String? = null,
)

@Serializable
internal data class SyncPullResponse(
    val changes: List<SyncRemoteChange>,
    @SerialName("next_cursor")
    val nextCursor: Long,
    @SerialName("has_more")
    val hasMore: Boolean,
)

@Serializable
internal data class SyncRemoteChange(
    @SerialName("change_id")
    val changeId: Long,
    @SerialName("entity_type")
    val entityType: String,
    @SerialName("entity_id")
    val entityId: String,
    val operation: String,
    val payload: JsonElement,
    val revision: Long,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
internal data class SyncReadingPositionPayload(
    val bookUuid: String,
    val contentHash: String?,
    val contentHashAlgorithm: String?,
    val position: ServerPosition,
)
