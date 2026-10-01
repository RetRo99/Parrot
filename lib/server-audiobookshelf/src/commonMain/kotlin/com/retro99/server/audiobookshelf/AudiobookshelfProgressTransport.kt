package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerPosition
import com.retro99.server.audiobookshelf.model.AbsEbookPlace
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaProgressApiModel
import com.retro99.server.audiobookshelf.model.absAudioPlace
import com.retro99.server.audiobookshelf.model.buildAbsEbookLocation
import com.retro99.server.audiobookshelf.model.parseAbsEbookLocation
import com.retro99.sync.domain.ProgressChangePage
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressLocator
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
 * Its `ebookLocation` is opaque: a CFI (web reader) or a JSON Readium locator (mobile apps).
 * Pulls read either shape and keep the raw value; pushes write the stored shape back, and never
 * a bare href (see [parseAbsEbookLocation] and [buildAbsEbookLocation]).
 *
 * @param storedEbookLocation the raw `ebookLocation` stored with a book's position, or null.
 * @param readingOrderHrefs a book's EPUB reading order when it's on this device, or null.
 * @param trackDurationsMs each audio file's length cached for a library item, or null.
 */
class AudiobookshelfProgressTransport(
    private val networkClient: ServerNetworkClient,
    private val storedEbookLocation: suspend (bookUuid: String) -> String? = { _ -> null },
    private val readingOrderHrefs: suspend (bookUuid: String) -> List<String>? = { _ -> null },
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
            val model = result.getOrElse { error ->
                throw IllegalStateException("Audiobookshelf progress fetch failed: $error")
            } ?: return@mapNotNull null
            remoteBookId to RemoteProgressSnapshot(
                entityId = remoteBookId,
                remoteBookId = remoteBookId,
                libraryBookId = model.libraryItemId ?: remoteBookId,
                kind = ProgressKind.AUDIO,
                snapshot = model.toProgressSnapshot(
                    trackDurationsMs = trackDurationsMs(remoteBookId),
                    readingOrderHrefs = { readingOrderHrefs(remoteBookId) },
                ),
                version = null,
                observedAt = model.lastUpdate?.toString(),
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
            val body = if (snapshot.hasAudio) {
                mutation.toAudioApiModel()
            } else {
                mutation.toEbookApiModel(
                    ebookLocation = buildAbsEbookLocation(
                        place = snapshot.toEbookPlace(),
                        storedRaw = snapshot.ebookLocationRaw
                            ?: storedEbookLocation(mutation.entityId),
                        readingOrderHrefs = { readingOrderHrefs(mutation.entityId) },
                    ),
                )
            }
            val result: CompletableResult = networkClient.patch(
                path = "/api/me/progress/${mutation.remoteBookId}",
                body = body,
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

private suspend fun AudiobookshelfMediaProgressApiModel.toProgressSnapshot(
    trackDurationsMs: List<Long>?,
    readingOrderHrefs: suspend () -> List<String>?,
): ProgressSnapshot {
    val place = absAudioPlace(currentTime, trackDurationsMs)
    val ebook = parseAbsEbookLocation(ebookLocation, ebookProgress, readingOrderHrefs)
    return ProgressSnapshot(
        timestamp = lastUpdate,
        createdAt = startedAt?.toString(),
        updatedAt = lastUpdate?.toString(),
        locator = ProgressLocator(
            href = ebook.href,
            type = ebook.type,
            title = null,
            target = null,
            cssSelector = ebook.cssSelector,
        ),
        audioTimestampMs = place.offsetMs,
        chapterIndex = if (place.isListening) place.trackIndex else ebook.spineIndex,
        progression = if (place.isListening) progress else ebook.progression,
        totalChapters = trackDurationsMs?.size.takeIf { _ -> place.isListening },
        totalDurationMs = duration?.let { value -> (value * 1000).toLong() },
        totalProgression = if (place.isListening) progress else ebook.totalProgression ?: progress,
        position = null,
        bookTimeMs = place.bookTimeMs,
        ebookLocationRaw = ebookLocation,
    )
}

/** Book-level audio values only; the ebook fields are left untouched (not sent). */
private fun ProgressMutation.toAudioApiModel(): AudiobookshelfMediaProgressApiModel {
    return AudiobookshelfMediaProgressApiModel(
        libraryItemId = libraryBookId ?: remoteBookId,
        duration = snapshot.absTotalDurationMs()?.div(1000.0),
        progress = snapshot.absProgress(),
        currentTime = snapshot.absBookTimeMs()?.div(1000.0),
        lastUpdate = snapshot.timestamp,
    )
}

/**
 * An ebook position: the location in the stored shape (or none, when it can't be built), and
 * always the whole-book progress, which every Audiobookshelf reader falls back to.
 */
private fun ProgressMutation.toEbookApiModel(
    ebookLocation: String?,
): AudiobookshelfMediaProgressApiModel {
    return AudiobookshelfMediaProgressApiModel(
        libraryItemId = libraryBookId ?: remoteBookId,
        progress = snapshot.totalProgression,
        lastUpdate = snapshot.timestamp,
        ebookLocation = ebookLocation,
        ebookProgress = snapshot.totalProgression,
    )
}

private fun ProgressSnapshot.toEbookPlace() = AbsEbookPlace(
    href = locator?.href,
    type = locator?.type,
    title = locator?.title,
    progression = progression,
    totalProgression = totalProgression,
    cssSelector = locator?.cssSelector,
    chapterIndex = chapterIndex,
)

internal fun ServerPosition.toProgressSnapshot(): ProgressSnapshot {
    return ProgressSnapshot(
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
