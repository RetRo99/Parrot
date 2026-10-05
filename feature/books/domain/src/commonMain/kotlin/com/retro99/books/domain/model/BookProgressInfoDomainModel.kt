package com.retro99.books.domain.model

/**
 * Represents progress and cache information for a book.
 */
data class BookProgressInfoDomainModel(
    val bookUuid: String,
    /**
     * Local reading progress as a value between 0.0 and 1.0.
     * Null if no local progress has been recorded.
     */
    val localProgression: Double?,
    /**
     * Remote reading progress as a value between 0.0 and 1.0.
     * Null if no remote progress exists or couldn't be fetched.
     */
    val remoteProgression: Double?,
    /**
     * Whether the ebook is cached locally.
     */
    val isEbookCached: Boolean,
    /**
     * Whether the audiobook is cached locally.
     */
    val isAudiobookCached: Boolean,
    /**
     * Whether the readaloud is cached locally.
     */
    val isReadaloudCached: Boolean,
    val chapterIndex: Int? = null,
    val totalChapters: Int? = null,
    val totalDurationMs: Long? = null,
    val bookTimeMs: Long? = null,
    val remoteObservedAt: String? = null,
    val remoteDeviceName: String? = null,
) {
    /**
     * Returns true if any media type is cached.
     */
    val hasAnyCached: Boolean
        get() = isEbookCached || isAudiobookCached || isReadaloudCached

    /**
     * Returns the display progress (prefers local if available).
     */
    val displayProgression: Double?
        get() = localProgression ?: remoteProgression

    /** The percent every surface shows for this book. See [progressPercentOf]. */
    val progressPercent: Int
        get() = progressPercentOf(displayProgression)

    /** The local progress as a percentage (0-100), or null if no local progress. */
    val localProgressPercent: Int?
        get() = localProgression?.let { fraction -> progressPercentOf(fraction) }

    /** The remote progress as a percentage (0-100), or null if no remote progress. */
    val remoteProgressPercent: Int?
        get() = remoteProgression?.let { fraction -> progressPercentOf(fraction) }

    /**
     * Returns true if both reconciled candidates exist and their exact progress
     * values differ. The shared sync engine decides which candidate is a clean
     * remote apply or a preserved dirty baseline; this model does not choose a
     * winner using a percentage threshold.
     */
    val hasConflict: Boolean
        get() {
            val local = localProgression ?: return false
            val remote = remoteProgression ?: return false
            return local != remote
        }
}
