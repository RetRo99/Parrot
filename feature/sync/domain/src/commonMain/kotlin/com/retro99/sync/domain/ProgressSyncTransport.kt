package com.retro99.sync.domain

/**
 * Backend-neutral progress transport used by the shared synchronization
 * engine. Implementations only perform remote I/O and response mapping.
 */
interface ProgressSyncTransport {
    val capabilities: ProgressTransportCapabilities

    suspend fun fetchProgress(remoteBookIds: Set<String>): Map<String, RemoteProgressSnapshot>

    suspend fun fetchChanges(cursor: String?, limit: Int): ProgressChangePage

    suspend fun pushProgress(mutations: List<ProgressMutation>): List<ProgressPushResult>
}

data class ProgressTransportCapabilities(
    val supportsBatching: Boolean,
    val maxBatchSize: Int?,
    val supportsConditionalWrites: Boolean,
    val supportsIdempotency: Boolean,
    val supportsChangeFeed: Boolean,
    val supportsRemoteFetch: Boolean,
)

data class ProgressMutation(
    val mutationId: String,
    val entityId: String,
    val remoteBookId: String,
    val libraryBookId: String?,
    val kind: ProgressKind,
    val snapshot: ProgressSnapshot,
    val baseVersion: String?,
    val observedAt: String?,
    /** Cloud-only origin metadata, created with the reading mutation. */
    val sourceDevice: ProgressSourceDevice? = null,
)

data class ProgressSourceDevice(
    val id: String,
    val name: String?,
)

data class ProgressSnapshot(
    val timestamp: Long?,
    val createdAt: String?,
    val updatedAt: String?,
    val locator: ProgressLocator?,
    val audioTimestampMs: Long?,
    val chapterIndex: Int?,
    val progression: Double?,
    val totalChapters: Int?,
    val totalDurationMs: Long?,
    val totalProgression: Double?,
    val position: Int?,
    /** Audiobooks: time from the start of the book; null when unknown. */
    val bookTimeMs: Long? = null,
    /** Audiobookshelf's raw `ebookLocation`, stored so a push can mirror its shape. */
    val ebookLocationRaw: String? = null,
)

data class ProgressLocator(
    val href: String?,
    val type: String?,
    val title: String?,
    val target: Int?,
    val cssSelector: String?,
)

enum class ProgressKind {
    EBOOK,
    AUDIO,
}

data class RemoteProgressSnapshot(
    val entityId: String?,
    val remoteBookId: String,
    val libraryBookId: String?,
    val kind: ProgressKind,
    val snapshot: ProgressSnapshot,
    val version: String?,
    val observedAt: String?,
    /**
     * Identifies the write this snapshot came from, for echo detection (§1.4, guard 2):
     * Storyteller's `timestamp`, Parrot Cloud's revision, null for Audiobookshelf.
     */
    val marker: String? = null,
    /** Display-only origin metadata; never participates in progress comparison or echo checks. */
    val sourceDevice: ProgressSourceDevice? = null,
)

data class ProgressChangePage(
    val changes: List<RemoteProgressSnapshot>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

sealed interface ProgressPushResult {
    val mutationId: String

    data class Accepted(
        override val mutationId: String,
        val version: String?,
    ) : ProgressPushResult

    data class Conflict(
        override val mutationId: String,
        val remote: RemoteProgressSnapshot,
    ) : ProgressPushResult

    data class Rejected(
        override val mutationId: String,
        val reason: String,
        val retryAfterMillis: Long? = null,
    ) : ProgressPushResult
}
