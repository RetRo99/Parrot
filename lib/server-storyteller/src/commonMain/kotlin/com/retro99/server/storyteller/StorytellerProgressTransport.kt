package com.retro99.server.storyteller

import com.github.michaelbull.result.fold
import com.github.michaelbull.result.get
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.storyteller.model.StorytellerPositionApiModel
import com.retro99.server.storyteller.model.toStorytellerApiModel
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.ProgressTransportCapabilities
import com.retro99.sync.domain.RemoteProgressSnapshot
import retro99.network.api.get
import retro99.network.api.post

/**
 * Storyteller's progress-only transport.
 *
 * The v2 positions endpoint exposes absolute snapshots, but no opaque
 * revision, change feed, or idempotency token. The shared engine must not
 * infer those capabilities from the endpoint shape.
 */
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
                        remoteBookId to apiModel.toRemoteProgressSnapshot(
                            remoteBookId = remoteBookId,
                            serverId = networkClient.serverId,
                        )
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
                body = mutation.toStorytellerServerPosition(networkClient.serverId)
                    .toStorytellerApiModel(),
            )
            result.fold(
                success = {
                    ProgressPushResult.Accepted(
                        mutationId = mutation.mutationId,
                        version = null,
                    )
                },
                failure = { error ->
                    if (error is AppError.ApiError && error.code == HTTP_CONFLICT) {
                        conflictOf(mutation)
                    } else {
                        ProgressPushResult.Rejected(
                            mutationId = mutation.mutationId,
                            reason = error.toString(),
                        )
                    }
                },
            )
        }
    }

    /**
     * Storyteller answers 409 when it already holds a position with a newer timestamp. That
     * is a conflict, not a failure to retry: take the server's position, which stops the
     * retries. If it can't be fetched, wait long before trying again.
     */
    private suspend fun conflictOf(mutation: ProgressMutation): ProgressPushResult {
        val current: AppResult<StorytellerPositionApiModel?> = networkClient
            .get(path = "/api/v2/books/${mutation.remoteBookId}/positions")
        val remote = current.get()?.toRemoteProgressSnapshot(
            remoteBookId = mutation.remoteBookId,
            serverId = networkClient.serverId,
        )
        return if (remote != null) {
            ProgressPushResult.Conflict(mutationId = mutation.mutationId, remote = remote)
        } else {
            ProgressPushResult.Rejected(
                mutationId = mutation.mutationId,
                reason = "Storyteller holds a newer position, which could not be fetched",
                retryAfterMillis = CONFLICT_RETRY_AFTER_MILLIS,
            )
        }
    }

    companion object {
        private const val HTTP_CONFLICT = 409

        /** How long a 409 waits when the server's position couldn't be fetched. */
        const val CONFLICT_RETRY_AFTER_MILLIS = 15L * 60 * 1000
    }
}
