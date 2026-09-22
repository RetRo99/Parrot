package com.retro99.server.storyteller

import com.github.michaelbull.result.fold
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerPosition
import com.retro99.server.storyteller.model.StorytellerPositionApiModel
import com.retro99.server.storyteller.model.toServerPosition
import com.retro99.server.storyteller.model.toStorytellerApiModel
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.ProgressTransportCapabilities
import com.retro99.sync.domain.ProgressLocator
import com.retro99.sync.domain.RemoteProgressSnapshot
import org.koin.core.annotation.Single
import retro99.network.api.get
import retro99.network.api.post

/**
 * Storyteller's progress-only transport.
 *
 * The v2 positions endpoint exposes absolute snapshots, but no opaque
 * revision, change feed, or idempotency token. The shared engine must not
 * infer those capabilities from the endpoint shape.
 */
@Single
class StorytellerProgressTransport(
    private val networkClient: ServerNetworkClient,
) : ProgressSyncTransport {

    override val capabilities = ProgressTransportCapabilities(
        supportsBatching = false,
        maxBatchSize = 1,
        supportsConditionalWrites = false,
        supportsIdempotency = false,
        supportsChangeFeed = false,
        supportsRemoteFetch = true,
    )

    override suspend fun fetchProgress(
        remoteBookIds: Set<String>,
    ): Map<String, RemoteProgressSnapshot> {
        return remoteBookIds.mapNotNull { remoteBookId ->
            val result: AppResult<StorytellerPositionApiModel?> = networkClient
                .get(path = "/api/v2/books/$remoteBookId/positions")
            result.fold(
                success = { apiModel ->
                    if (apiModel == null) {
                        null
                    } else {
                        val position = apiModel.toServerPosition(
                            bookUuid = remoteBookId,
                            serverId = networkClient.serverId,
                        )
                        remoteBookId to position.toRemoteProgressSnapshot(remoteBookId)
                    }
                },
                failure = { error ->
                    throw IllegalStateException("Storyteller progress fetch failed: $error")
                },
            )
        }.toMap()
    }

    override suspend fun fetchChanges(cursor: String?, limit: Int): ProgressChangePage {
        throw UnsupportedOperationException(
            "Storyteller does not expose a progress change feed",
        )
    }

    override suspend fun pushProgress(
        mutations: List<ProgressMutation>,
    ): List<ProgressPushResult> {
        return mutations.map { mutation ->
            val result: CompletableResult = networkClient.post(
                path = "/api/v2/books/${mutation.remoteBookId}/positions",
                body = mutation.toServerPosition().toStorytellerApiModel(),
            )
            result.fold(
                success = {
                    ProgressPushResult.Accepted(
                        mutationId = mutation.mutationId,
                        version = null,
                    )
                },
                failure = { error ->
                    ProgressPushResult.Rejected(
                        mutationId = mutation.mutationId,
                        reason = error.toString(),
                    )
                },
            )
        }
    }

    private fun ProgressMutation.toServerPosition(): ServerPosition {
        return snapshot.toServerPosition(
            bookUuid = entityId,
            serverId = networkClient.serverId,
            libraryBookId = libraryBookId,
        )
    }

    private fun ProgressSnapshot.toServerPosition(
        bookUuid: String,
        serverId: String,
        libraryBookId: String?,
    ): ServerPosition {
        return ServerPosition(
            bookUuid = bookUuid,
            serverId = serverId,
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
            position = position,
            cssSelector = locator?.cssSelector,
        )
    }

    private fun ServerPosition.toRemoteProgressSnapshot(
        remoteBookId: String,
    ): RemoteProgressSnapshot {
        return RemoteProgressSnapshot(
            entityId = bookUuid,
            remoteBookId = remoteBookId,
            libraryBookId = libraryBookId,
            kind = ProgressKind.EBOOK,
            snapshot = ProgressSnapshot(
                timestamp = timestamp,
                createdAt = createdAt,
                updatedAt = updatedAt,
                locator = ProgressLocator(
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
                position = position,
            ),
            version = null,
            observedAt = updatedAt ?: createdAt,
        )
    }
}
