package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookProgressInfoDomainModel

/**
 * UI model for book progress and cache information.
 */
data class BookProgressInfoUiModel(
    val bookUuid: String,
    /**
     * Local reading progress as a value between 0.0 and 1.0.
     * Null if no local progress has been recorded.
     */
    val localProgression: Double?,
    /**
     * Remote reading progress as a value between 0.0 and 1.0.
     * Null if no remote progress exists.
     */
    val remoteProgression: Double?,
    /**
     * Whether any media type is cached locally.
     */
    val hasAnyCached: Boolean,
    val hasConflict: Boolean = localProgression != null && remoteProgression != null &&
        localProgression != remoteProgression,
    val chapterIndex: Int? = null,
    val totalChapters: Int? = null,
    val totalDurationMs: Long? = null,
    val bookTimeMs: Long? = null,
    val remoteObservedAt: String? = null,
) {
    /**
     * Returns the display progress (prefers local) as a percentage (0-100).
     */
    val progressPercent: Int
        get() = ((displayProgression ?: 0.0) * 100).toInt()

    /**
     * Returns the local progress as a percentage (0-100), or null if no local progress.
     */
    val localProgressPercent: Int?
        get() = localProgression?.let { (it * 100).toInt() }

    /**
     * Returns the remote progress as a percentage (0-100), or null if no remote progress.
     */
    val remoteProgressPercent: Int?
        get() = remoteProgression?.let { (it * 100).toInt() }

    /**
     * Returns true if there's a conflict between local and remote progress.
     */

    /**
     * Returns the progress to display (prefers local if available).
     */
    val displayProgression: Double?
        get() = localProgression ?: remoteProgression
}

fun BookProgressInfoDomainModel.toUiModel(): BookProgressInfoUiModel {
    return BookProgressInfoUiModel(
        bookUuid = bookUuid,
        localProgression = localProgression,
        remoteProgression = remoteProgression,
        hasAnyCached = hasAnyCached,
        hasConflict = hasConflict,
        chapterIndex = chapterIndex,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        bookTimeMs = bookTimeMs,
        remoteObservedAt = remoteObservedAt,
    )
}
