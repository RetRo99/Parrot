package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.progressPercentOf

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
    val remoteDeviceName: String? = null,
) {
    /**
     * Returns the progress to display (prefers local if available).
     */
    val displayProgression: Double?
        get() = localProgression ?: remoteProgression

    /**
     * Returns the display progress (prefers local) as a percentage (0-100).
     * The same [progressPercentOf] the domain model uses, so no surface rounds differently.
     */
    val progressPercent: Int
        get() = progressPercentOf(displayProgression)

    /**
     * Returns the local progress as a percentage (0-100), or null if no local progress.
     */
    val localProgressPercent: Int?
        get() = localProgression?.let { fraction -> progressPercentOf(fraction) }

    /**
     * Returns the remote progress as a percentage (0-100), or null if no remote progress.
     */
    val remoteProgressPercent: Int?
        get() = remoteProgression?.let { fraction -> progressPercentOf(fraction) }
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
        remoteDeviceName = remoteDeviceName,
    )
}
