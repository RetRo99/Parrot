package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.server.api.ServerPosition
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import com.retro99.sync.domain.ProgressSourceDevice
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.ProgressTransportCapabilities
import com.retro99.sync.domain.RemoteProgressSnapshot
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

/**
 * Supabase RPC transport for reading progress.
 *
 * This class maps the shared transport contract to RPC payloads only. Queue
 * ownership, local persistence, retries, and conflict decisions remain in the
 * shared synchronization layer.
 */
@Single(binds = [ProgressSyncTransport::class])
class ParrotCloudProgressTransport(
    @Provided private val clientProvider: SupabaseClientProvider,
) : ProgressSyncTransport {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override val capabilities = ProgressTransportCapabilities(
        supportsBatching = true,
        maxBatchSize = MAX_BATCH_SIZE,
        supportsConditionalWrites = true,
        supportsIdempotency = true,
        supportsChangeFeed = true,
        supportsRemoteFetch = false,
    )

    override suspend fun fetchProgress(
        remoteBookIds: Set<String>,
    ): Map<String, RemoteProgressSnapshot> {
        if (!capabilities.supportsRemoteFetch) {
            throw UnsupportedOperationException(
                "Parrot Cloud does not expose a direct progress fetch",
            )
        }
        return emptyMap()
    }

    override suspend fun fetchChanges(cursor: String?, limit: Int): ProgressChangePage {
        val response = clientProvider.client.postgrest
            .rpc(
                "pull_sync_changes",
                buildJsonObject {
                    put("cursor", cursor?.toLongOrNull() ?: 0L)
                    put("limit", limit.coerceIn(1, MAX_BATCH_SIZE))
                },
            )
            .decodeAs<ParrotCloudPullResponse>()

        val changes = response.changes
            .filter { change -> change.entityType == ENTITY_TYPE_READING_POSITION }
            .map { change ->
                val payload = json.decodeFromJsonElement<ParrotCloudReadingPositionPayload>(change.payload)
                payload.toRemoteProgressSnapshot(change.revision)
            }
        return ProgressChangePage(
            changes = changes,
            nextCursor = response.nextCursor.toString(),
            hasMore = response.hasMore,
        )
    }

    override suspend fun pushProgress(
        mutations: List<ProgressMutation>,
    ): List<ProgressPushResult> {
        if (mutations.isEmpty()) return emptyList()

        val response = clientProvider.client.postgrest
            .rpc(
                "push_sync_changes",
                buildJsonObject {
                    put(
                        "mutations",
                        json.encodeToJsonElement(
                            mutations.map { mutation -> mutation.toRequest() },
                        ),
                    )
                    put("client_cursor", 0L)
                },
            )
            .decodeAs<List<ParrotCloudMutationResponse>>()

        return response.mapNotNull { result ->
            when (result.status) {
                STATUS_ACCEPTED -> ProgressPushResult.Accepted(
                    mutationId = result.mutationId,
                    version = result.revision?.toString(),
                )

                STATUS_CONFLICT -> {
                    val payload = result.payload
                    if (payload == null) {
                        ProgressPushResult.Rejected(
                            mutationId = result.mutationId,
                            reason = "Parrot Cloud conflict response omitted payload",
                        )
                    } else {
                        val remote = json.decodeFromJsonElement<ParrotCloudReadingPositionPayload>(payload)
                            .toRemoteProgressSnapshot(result.revision)
                        ProgressPushResult.Conflict(result.mutationId, remote)
                    }
                }

                else -> ProgressPushResult.Rejected(
                    mutationId = result.mutationId,
                    reason = result.reason ?: "Parrot Cloud mutation rejected",
                )
            }
        }
    }

    private fun ProgressMutation.toRequest(): ParrotCloudMutationRequest {
        return ParrotCloudMutationRequest(
            mutationId = mutationId,
            entityType = ENTITY_TYPE_READING_POSITION,
            entityId = entityId,
            operation = OPERATION_UPSERT,
            payload = json.encodeToJsonElement(
                ParrotCloudReadingPositionPayload(
                    libraryBookId = remoteBookId,
                    position = snapshot.toServerPosition(
                        bookUuid = remoteBookId,
                        libraryBookId = remoteBookId,
                    ),
                    sourceDevice = sourceDevice?.let { device ->
                        ParrotCloudSourceDevice(id = device.id, name = device.name)
                    },
                ),
            ),
            baseRevision = baseVersion?.toLongOrNull(),
            createdAt = observedAt
                ?: snapshot.updatedAt
                ?: Clock.System.now().toString(),
        )
    }

    private fun ParrotCloudReadingPositionPayload.toRemoteProgressSnapshot(
        revision: Long?,
    ): RemoteProgressSnapshot {
        return RemoteProgressSnapshot(
            entityId = libraryBookId,
            remoteBookId = libraryBookId,
            libraryBookId = libraryBookId,
            kind = ProgressKind.EBOOK,
            snapshot = position.toProgressSyncSnapshot(),
            version = revision?.toString(),
            observedAt = position.updatedAt ?: position.createdAt,
            marker = revision?.toString(),
            sourceDevice = sourceDevice?.let { device ->
                ProgressSourceDevice(id = device.id, name = device.name)
            },
        )
    }

    private companion object {
        const val ENTITY_TYPE_READING_POSITION = "reading_position"
        const val OPERATION_UPSERT = "upsert"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_CONFLICT = "conflict"
        const val MAX_BATCH_SIZE = 50
    }
}

internal fun ProgressSnapshot.toServerPosition(
    bookUuid: String,
    libraryBookId: String,
): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = PARROT_CLOUD_SERVER_ID,
        libraryBookId = libraryBookId,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locator?.href,
        locatorType = locator?.type,
        locatorTitle = locator?.title,
        locatorTarget = locator?.target,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        bookTimeMs = bookTimeMs,
        position = position,
        cssSelector = locator?.cssSelector,
    )
}

internal fun ServerPosition.toProgressSyncSnapshot(): ProgressSnapshot {
    return ProgressSnapshot(
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locator = com.retro99.sync.domain.ProgressLocator(
            href = locatorHref,
            type = locatorType,
            title = locatorTitle,
            target = locatorTarget,
            cssSelector = cssSelector,
        ),
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        bookTimeMs = bookTimeMs,
        position = position,
    )
}

@Serializable
private data class ParrotCloudMutationRequest(
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
private data class ParrotCloudMutationResponse(
    @SerialName("mutation_id")
    val mutationId: String,
    val status: String,
    val revision: Long? = null,
    val payload: JsonElement? = null,
    val reason: String? = null,
)

@Serializable
private data class ParrotCloudPullResponse(
    val changes: List<ParrotCloudRemoteChange>,
    @SerialName("next_cursor")
    val nextCursor: Long,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

@Serializable
private data class ParrotCloudRemoteChange(
    @SerialName("entity_type")
    val entityType: String,
    val payload: JsonElement,
    val revision: Long,
)
