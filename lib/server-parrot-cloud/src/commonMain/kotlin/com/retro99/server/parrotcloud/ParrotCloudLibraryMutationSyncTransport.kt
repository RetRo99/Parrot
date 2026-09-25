package com.retro99.server.parrotcloud

import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.LibraryGroupDecisionCodec
import com.retro99.sync.domain.LibraryGroupSyncReadiness
import com.retro99.sync.domain.LibraryMutationSyncTransport
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Supabase RPC mapping for Parrot library mutations.
 *
 * This adapter performs no outbox or local-database work. It only translates
 * the shared request/page models to and from the RPC wire format.
 */
@Single(binds = [LibraryMutationSyncTransport::class])
class ParrotCloudLibraryMutationSyncTransport(
    @Provided private val rpc: ParrotCloudLibraryMutationRpc,
) : LibraryMutationSyncTransport {
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

        val groupMutations = mutations.filter { mutation ->
            mutation.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION
        }
        val regularMutations = mutations.filterNot { mutation ->
            mutation.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION
        }
        val bookRemovals = regularMutations.filter { mutation ->
            mutation.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK &&
                mutation.operation == SyncOutboxEntry.OPERATION_DELETE
        }
        val standardMutations = regularMutations.filterNot { mutation ->
            mutation in bookRemovals
        }
        val regularResponses = pushToRpc(
            rpcName = "push_sync_changes",
            mutations = standardMutations,
            cursor = cursor,
        )
        val standardResponsesByMutationId = regularResponses.associateBy { response ->
            response.mutationId
        }
        val latestAcceptedBookRevisionById = standardMutations.mapNotNull { mutation ->
            if (
                mutation.entityType != SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK ||
                mutation.operation != SyncOutboxEntry.OPERATION_UPSERT
            ) {
                return@mapNotNull null
            }
            val response = standardResponsesByMutationId[mutation.mutationId]
                ?: return@mapNotNull null
            if (response.status != STATUS_ACCEPTED) return@mapNotNull null
            response.revision?.let { revision -> mutation.entityId to revision }
        }.toMap()
        val rebasedBookRemovals = bookRemovals.map { mutation ->
            val latestRevision = latestAcceptedBookRevisionById[mutation.entityId]
            if (latestRevision == null) mutation else mutation.copy(
                baseRevision = latestRevision,
            )
        }
        val bookRemovalResponses = pushToRpc(
            rpcName = "delete_cloud_books",
            mutations = rebasedBookRemovals,
            cursor = cursor,
        )
        val groupResponses = pushToRpc(
            rpcName = "push_library_group_decisions",
            mutations = groupMutations,
            cursor = cursor,
        )
        return regularResponses + bookRemovalResponses + groupResponses
    }

    private suspend fun pushToRpc(
        rpcName: String,
        mutations: List<SyncMutationRequest>,
        cursor: String?,
    ): List<SyncMutationResponse> {
        if (mutations.isEmpty()) return emptyList()

        val response = rpc.invoke(
            function = rpcName,
            arguments = buildJsonObject {
                put(
                    "mutations",
                    json.encodeToJsonElement(mutations.map { it.toRpcRequest() }),
                )
                put("client_cursor", cursor?.toLongOrNull() ?: 0L)
            },
        )
        return json.decodeFromJsonElement<List<ParrotCloudLibraryMutationResponse>>(response)
            .map { result ->
                SyncMutationResponse(
                    mutationId = result.mutationId,
                    status = result.status,
                    cloudBookId = result.cloudBookId,
                    revision = result.revision,
                    payload = result.payload?.let(json::encodeToString),
                    reason = result.reason,
                    retryAfterMillis = result.retryAfterMillis,
                )
            }
    }

    override suspend fun pull(
        cursor: String?,
        limit: Int,
    ): SyncChangePage {
        val response = json.decodeFromJsonElement<ParrotCloudLibraryMutationPullResponse>(
            rpc.invoke(
                function = "pull_sync_changes",
                arguments = buildJsonObject {
                    put("cursor", cursor?.toLongOrNull() ?: 0L)
                    put("limit", limit.coerceIn(1, MAX_BATCH_SIZE))
                },
            ),
        )

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

    private fun SyncMutationRequest.toRpcRequest() = ParrotCloudLibraryMutationRequest(
        mutationId = mutationId,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = json.parseToJsonElement(
            if (entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION) {
                val readiness = LibraryGroupDecisionCodec.encodeWireOrDefer(payload)
                (readiness as? LibraryGroupSyncReadiness.Ready)?.payload
                    ?: error("A non-portable grouping decision must remain local")
            } else {
                payload
            },
        ),
        baseRevision = baseRevision,
        createdAt = createdAt,
    )

    private companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val MAX_BATCH_SIZE = 50
    }
}

interface ParrotCloudLibraryMutationRpc {
    suspend fun invoke(
        function: String,
        arguments: JsonObject,
    ): JsonElement
}

@Single(binds = [ParrotCloudLibraryMutationRpc::class])
internal class SupabaseParrotCloudLibraryMutationRpc(
    @Provided private val clientProvider: SupabaseClientProvider,
) : ParrotCloudLibraryMutationRpc {
    override suspend fun invoke(
        function: String,
        arguments: JsonObject,
    ): JsonElement = clientProvider.client.postgrest
        .rpc(function, arguments)
        .decodeAs()
}

@Serializable
private data class ParrotCloudLibraryMutationRequest(
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
private data class ParrotCloudLibraryMutationResponse(
    @SerialName("mutation_id")
    val mutationId: String,
    val status: String,
    @SerialName("cloud_book_id")
    val cloudBookId: String? = null,
    val revision: Long? = null,
    val payload: JsonElement? = null,
    val reason: String? = null,
    @SerialName("retry_after_ms")
    val retryAfterMillis: Long? = null,
)

@Serializable
private data class ParrotCloudLibraryMutationPullResponse(
    val changes: List<ParrotCloudLibraryMutationRemoteChange>,
    @SerialName("next_cursor")
    val nextCursor: Long,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

@Serializable
private data class ParrotCloudLibraryMutationRemoteChange(
    @SerialName("entity_type")
    val entityType: String,
    val payload: JsonElement,
    val revision: Long,
)
