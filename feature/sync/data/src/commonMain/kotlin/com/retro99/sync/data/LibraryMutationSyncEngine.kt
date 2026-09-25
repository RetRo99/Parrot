package com.retro99.sync.data

import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.LibraryMutationSyncTransport
import com.retro99.sync.domain.SyncMutationRequest
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Shared state machine for non-progress library-mutation outbox entries.
 *
 * The transport only performs RPC mapping. Backend-specific metadata updates
 * are supplied through [LibraryMutationApplier], while dispatch state, retry
 * scheduling, and unresolved-result handling remain centralized here.
 */
@Single
class LibraryMutationSyncEngine(
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
) {
    suspend fun push(
        entries: List<SyncOutboxEntry>,
        transport: LibraryMutationSyncTransport,
        applier: LibraryMutationApplier,
        cursor: String?,
    ): LibraryMutationPushSummary {
        if (entries.isEmpty()) return LibraryMutationPushSummary()

        entries.forEach { entry ->
            syncOutboxDatabase.markDispatched(entry.mutationId)
        }

        val requests = entries.map { entry ->
            SyncMutationRequest(
                mutationId = entry.mutationId,
                entityType = entry.entityType,
                entityId = entry.entityId,
                operation = entry.operation,
                payload = entry.payload,
                baseRevision = entry.baseRevision,
                createdAt = entry.createdAt,
            )
        }
        val responses = try {
            transport.push(requests, cursor)
        } catch (exception: CancellationException) {
            throw exception
        }
        val responsesByMutationId = responses.associateBy { response -> response.mutationId }
        var acknowledgedCount = 0
        var conflictCount = 0
        var rejectedCount = 0
        var retryCount = 0
        var unresolvedCount = 0

        entries.forEach { entry ->
            when (val response = responsesByMutationId[entry.mutationId]) {
                null -> unresolvedCount++

                else -> when (response.status) {
                    STATUS_ACCEPTED -> {
                        applier.onAccepted(entry, response)
                        syncOutboxDatabase.delete(entry.mutationId)
                        acknowledgedCount++
                    }

                    STATUS_CONFLICT -> {
                        applier.onConflict(entry, response)
                        syncOutboxDatabase.markConflict(
                            mutationId = entry.mutationId,
                            error = response.reason ?: "Remote mutation conflict",
                        )
                        conflictCount++
                    }

                    STATUS_REJECTED -> {
                        syncOutboxDatabase.markConflict(
                            mutationId = entry.mutationId,
                            error = response.reason ?: "Remote mutation rejected",
                        )
                        rejectedCount++
                    }

                    STATUS_RETRYABLE -> {
                        scheduleRetry(entry, response)
                        retryCount++
                    }

                    else -> {
                        scheduleRetry(entry, response)
                        retryCount++
                    }
                }
            }
        }

        return LibraryMutationPushSummary(
            acknowledgedCount = acknowledgedCount,
            conflictCount = conflictCount,
            rejectedCount = rejectedCount,
            retryCount = retryCount,
            unresolvedCount = unresolvedCount,
        )
    }

    private suspend fun scheduleRetry(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        val delaySeconds = response.retryAfterMillis
            ?.div(1000L)
            ?.coerceAtLeast(1L)
            ?: (1L shl min(entry.attemptCount.coerceAtLeast(0), MAX_BACKOFF_POWER))
        syncOutboxDatabase.recordFailure(
            mutationId = entry.mutationId,
            nextAttemptAt = Clock.System.now().plus(delaySeconds.seconds).toString(),
            error = response.reason ?: "Remote mutation rejected",
        )
    }

    private companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_CONFLICT = "conflict"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_RETRYABLE = "retryable"
        const val MAX_BACKOFF_POWER = 6
    }
}

interface LibraryMutationApplier {
    suspend fun onAccepted(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    )

    suspend fun onConflict(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    )
}

data class LibraryMutationPushSummary(
    val acknowledgedCount: Int = 0,
    val conflictCount: Int = 0,
    val rejectedCount: Int = 0,
    val retryCount: Int = 0,
    val unresolvedCount: Int = 0,
)
