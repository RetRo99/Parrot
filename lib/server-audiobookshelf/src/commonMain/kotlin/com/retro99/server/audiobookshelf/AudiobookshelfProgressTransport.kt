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

/**
 * Audiobookshelf's `currentTime`, `duration` and `progress` are book-level: `currentTime` is
 * the time from the start of the book. Our audio positions keep the file index and the offset
 * in that file next to the book time (`bookTimeMs`), so a per-file time is never sent.
 *
 * @param trackDurationsMs each audio file's length cached for a library item, or null.
 */
class AudiobookshelfProgressTransport(
    private val networkClient: ServerNetworkClient,
    private val trackDurationsMs: suspend (remoteBookId: String) -> List<Long>? = { _ -> null },
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
            val durations = if (result.isOk) trackDurationsMs(remoteBookId) else null
            result.fold(
                success = { apiModel ->
                    apiModel?.let { model ->
                        remoteBookId to RemoteProgressSnapshot(
                            entityId = remoteBookId,
                            remoteBookId = remoteBookId,
                            libraryBookId = model.libraryItemId ?: remoteBookId,
                            kind = ProgressKind.AUDIO,
                            snapshot = model.toProgressSnapshot(durations),
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
            val snapshot = mutation.snapshot
            if (snapshot.hasAudio && snapshot.absBookTimeMs() == null) {
                // A multi-file position without its book time: sending the file offset would
                // move Audiobookshelf's apps to the wrong place.
                return@map ProgressPushResult.Rejected(
                    mutationId = mutation.mutationId,
                    reason = "Audiobook position without its time from the start of the book",
                    retryAfterMillis = MISSING_BOOK_TIME_RETRY_AFTER_MILLIS,
                )
            }
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

    companion object {
        /** How long an audio position without book time waits; the next save replaces it. */
        const val MISSING_BOOK_TIME_RETRY_AFTER_MILLIS = 60L * 60 * 1000
    }
}

private fun AudiobookshelfMediaProgressApiModel.toProgressSnapshot(
    trackDurationsMs: List<Long>?,
): ProgressSnapshot {
    val place = absAudioPlace(currentTime, trackDurationsMs)
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
        audioTimestampMs = place.offsetMs,
        chapterIndex = place.trackIndex,
        progression = progress,
        totalChapters = trackDurationsMs?.size,
        totalDurationMs = duration?.let { value -> (value * 1000).toLong() },
        totalProgression = if (place.isListening) progress else ebookProgress ?: progress,
        position = null,
        bookTimeMs = place.bookTimeMs,
    )
}

private fun ProgressMutation.toApiModel(): AudiobookshelfMediaProgressApiModel {
    return AudiobookshelfMediaProgressApiModel(
        libraryItemId = libraryBookId ?: remoteBookId,
        duration = snapshot.absTotalDurationMs()?.div(1000.0),
        progress = if (snapshot.hasAudio) snapshot.absProgress() else snapshot.progression,
        currentTime = snapshot.absBookTimeMs()?.div(1000.0),
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
        bookTimeMs = bookTimeMs,
        position = position,
    )
}

private val ProgressSnapshot.hasAudio: Boolean
    get() = audioTimestampMs != null || bookTimeMs != null

private val ProgressSnapshot.isSingleFile: Boolean
    get() = (totalChapters ?: 1) <= 1 && (chapterIndex ?: 0) == 0

/** The time from the start of the book; a single file's offset is its book time. */
private fun ProgressSnapshot.absBookTimeMs(): Long? =
    bookTimeMs ?: audioTimestampMs?.takeIf { _ -> isSingleFile }

/** Whole-book length; before book time existed, only a single file's length was the book's. */
private fun ProgressSnapshot.absTotalDurationMs(): Long? =
    totalDurationMs?.takeIf { _ -> bookTimeMs != null || isSingleFile }

private fun ProgressSnapshot.absProgress(): Double? =
    totalProgression?.takeIf { _ -> bookTimeMs != null || isSingleFile }
