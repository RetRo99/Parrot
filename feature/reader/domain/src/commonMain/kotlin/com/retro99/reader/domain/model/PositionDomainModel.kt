package com.retro99.reader.domain.model

import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.TextAnchor

/**
 * Represents the reading position for a book.
 * This is a flattened model that combines locator and location data.
 */
data class PositionDomainModel(
    val bookUuid: String,
    val serverId: String,
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
    val origin: PositionOrigin = PositionOrigin.User,
    /** When the reading happened. Null means now, when the position is saved. */
    val observedAt: String? = null,
    /** Text around an ebook position, captured by the reader. Stored on this device only. */
    val textAnchor: TextAnchor? = null,
    /**
     * Audiobooks: time from the start of the book. [audioTimestampMs] and [chapterIndex] are the
     * offset in the current file and its index; [totalDurationMs] and [totalProgression] are
     * whole-book. Null when the files' lengths aren't known.
     */
    val bookTimeMs: Long? = null,
    val localGeneration: Long = 0L,
    val remoteRevision: Long? = null,
    val deviceName: String? = null,
    val sourceDeviceId: String? = null,
    val ebookLocationRaw: String? = null,
)

/** Reading-place equality shared by restoration and the positions panel; excludes attribution. */
fun PositionDomainModel.isSameReadingPlaceAs(other: PositionDomainModel): Boolean =
    locatorHref == other.locatorHref && locatorType == other.locatorType &&
        locatorTarget == other.locatorTarget && cssSelector == other.cssSelector &&
        audioTimestampMs == other.audioTimestampMs && chapterIndex == other.chapterIndex &&
        progression == other.progression && totalProgression == other.totalProgression &&
        bookTimeMs == other.bookTimeMs && position == other.position
