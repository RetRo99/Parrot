package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.fold
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerPosition
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaProgressApiModel
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.ProgressTransportCapabilities
import com.retro99.sync.domain.RemoteProgressSnapshot
import retro99.network.api.get
import retro99.network.api.patch

class AudiobookshelfProgressTransport(
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
            val result = networkClient.get<AudiobookshelfMediaProgressApiModel?>(
                path = "/api/me/progress/$remoteBookId",
            )
            result.fold(
                success = { apiModel ->
                    apiModel?.let { model ->
                        remoteBookId to RemoteProgressSnapshot(
                            entityId = remoteBookId,
                            remoteBookId = remoteBookId,
                            libraryBookId = model.libraryItemId ?: remoteBookId,
                            kind = ProgressKind.AUDIO,
                            snapshot = model.toProgressSnapshot(),
                            version = null,
                            observedAt = model.lastUpdate?.toString(),
                        )
                    }
                },
                failure = { error ->
                    throw IllegalStateException("Audiobookshelf progress fetch failed: $error")
                },
            )
        }.toMap()
    }

    override suspend fun fetchChanges(cursor: String?, limit: Int): ProgressChangePage {
        throw UnsupportedOperationException(
            "Audiobookshelf does not expose a progress change feed",
        )
    }

    override suspend fun pushProgress(
        mutations: List<ProgressMutation>,
    ): List<ProgressPushResult> {
        return mutations.map { mutation ->
            val result: CompletableResult = networkClient.patch(
                path = "/api/me/progress/${mutation.remoteBookId}",
                body = mutation.toApiModel(),
            )
            result.fold(
                success = {
                    ProgressPushResult.Accepted(mutationId = mutation.mutationId, version = null)
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
}

private fun AudiobookshelfMediaProgressApiModel.toProgressSnapshot(): ProgressSnapshot {
    return ProgressSnapshot(
        timestamp = lastUpdate,
        createdAt = startedAt?.toString(),
        updatedAt = lastUpdate?.toString(),
        locator = com.retro99.sync.domain.ProgressLocator(
            href = ebookLocation,
            type = null,
            title = null,
            target = null,
            cssSelector = null,
        ),
        audioTimestampMs = currentTime?.let { time -> (time * 1000).toLong() },
        chapterIndex = null,
        progression = progress,
        totalChapters = null,
        totalDurationMs = duration?.let { value -> (value * 1000).toLong() },
        totalProgression = ebookProgress ?: progress,
        position = null,
    )
}

private fun ProgressMutation.toApiModel(): AudiobookshelfMediaProgressApiModel {
    return AudiobookshelfMediaProgressApiModel(
        libraryItemId = libraryBookId ?: remoteBookId,
        duration = snapshot.totalDurationMs?.div(1000.0),
        progress = snapshot.progression,
        currentTime = snapshot.audioTimestampMs?.div(1000.0),
        lastUpdate = snapshot.timestamp,
        ebookLocation = snapshot.locator?.href,
        ebookProgress = snapshot.totalProgression ?: snapshot.progression,
    )
}

internal fun ServerPosition.toProgressSnapshot(): ProgressSnapshot {
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
        position = position,
    )
}
