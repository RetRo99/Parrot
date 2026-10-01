package com.retro99.server.api

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Server-aware reader repository interface for reading progress.
 * Each server type implements this with its own API and local caching strategy.
 *
 * This interface owns both local and remote position data:
 * - Remote operations sync with the server (Storyteller, etc.)
 * - Local operations manage the local cache
 * - Combined operations coordinate between local and remote
 */
interface ServerReaderRepository {
    /**
     * The server ID this repository is associated with.
     */
    val serverId: String

    // ==================== Read Operations ====================

    /**
     * Gets the reading position for a book.
     * Uses remote-first with local cache fallback strategy.
     *
     * @param bookUuid The UUID of the book
     * @return The position (preferring remote, falling back to local cache)
     */
    suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?>

    /**
     * Gets the locally cached reading position for a book.
     *
     * @param bookUuid The UUID of the book
     * @return The local position or null if not cached
     */
    suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?>

    /**
     * Saves the reading position only to local cache, without syncing to remote.
     * Used when accepting a remote position to avoid re-posting it to the server.
     *
     * @param position The position to save locally
     */
    suspend fun saveLocalPosition(position: ServerPosition): CompletableResult

    /**
     * Commits the local position and durable delivery intent together.
     * Remote delivery is performed later by the shared sync destination.
     */
    suspend fun saveLocalPositionWithSync(
        bookUuid: String,
        position: ServerPosition,
    ): CompletableResult

    // ==================== Remote-only Operations ====================

    /**
     * Gets the reading position for a book directly from the remote server.
     * Does not use or update the local cache.
     *
     * @param bookUuid The UUID of the book
     * @return The position from the server, or null if not found
     */
    suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?>
}

/**
 * A reading position associated with a specific server.
 * This is a server-agnostic representation that each server implementation
 * converts to/from its own API format.
 */
@Serializable
data class ServerPosition(
    val bookUuid: String,
    val serverId: String,
    val libraryBookId: String? = null,
    val timestamp: Long?,
    val createdAt: String?,
    val updatedAt: String?,
    // Locator fields
    val locatorHref: String?,
    val locatorType: String?,
    val locatorTitle: String?,
    val locatorTarget: Int?,
    // Location fields
    val audioTimestampMs: Long?,
    val chapterIndex: Int?,
    val progression: Double?,
    val totalChapters: Int?,
    val totalDurationMs: Long?,
    val totalProgression: Double?,
    val position: Int?,
    val cssSelector: String? = null,
    val remoteRevision: Long? = null,
    // Local-only fields. @Transient keeps them out of every payload sent to a server.
    @Transient
    val origin: PositionOrigin = PositionOrigin.User,
    /** When the reading happened, which isn't necessarily when it was saved. */
    @Transient
    val observedAt: String? = null,
    @Transient
    val textAnchor: TextAnchor? = null,
)
