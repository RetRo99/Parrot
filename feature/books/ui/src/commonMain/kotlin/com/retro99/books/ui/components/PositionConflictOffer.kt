package com.retro99.books.ui.components

import androidx.compose.runtime.Composable
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.ServerRegistry
import com.retro99.sync.domain.ObservedTime
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.position_conflict_chapter_labelled
import resources.translations.position_conflict_chapter_only
import resources.translations.position_conflict_percent_word
import kotlin.math.roundToLong

/**
 * One candidate of a position conflict, ready to be shown on a card: the percentage,
 * the chapter, the "where and when" line and which side is newer (spec §3).
 */
data class PositionOffer(
    val percent: Int,
    val chapter: String?,
    /** "This phone · 12 minutes ago", "Storyteller · 3 hours ago", "Pixel Tablet · ...". */
    val whereWhen: String?,
    /** The newer position: it is listed first and carries the "Latest" pill. */
    val isLatest: Boolean,
)

/** The display name of the source behind the remote candidate: the server's own name —
 * never "Storyteller (Storyteller)", never the word "Server" (spec §4).
 */
suspend fun conflictSourceName(
    serverId: String,
    registry: ServerRegistry?,
): String = when (serverId) {
    LOCAL_SERVER_ID, PARROT_CLOUD_SERVER_ID -> "Parrot Cloud"
    else -> registry?.getServer(serverId)?.name.orEmpty()
}

@Composable
fun positionOffer(
    position: PositionDomainModel,
    /** The source's display name; ignored when the position stored a device name. */
    whereName: String?,
    isLatest: Boolean,
    /** False keeps [whereName] even when the position recorded a device name (this device). */
    preferDeviceName: Boolean = true,
): PositionOffer = PositionOffer(
    percent = displayPercentOf(position),
    chapter = conflictChapterLine(position),
    whereWhen = listOfNotNull(
        when {
            !preferDeviceName -> whereName?.takeIf { it.isNotBlank() }
            else -> position.deviceName?.takeIf { it.isNotBlank() } ?: whereName?.takeIf { it.isNotBlank() }
        },
        position.observedAt?.let { observed ->
            ObservedTime.toEpochMillis(observed)?.let { relativeTimeText(it) }
        },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · "),
    isLatest = isLatest,
)

/** "Chapter 8, The Crossing", or the chapter title alone, or "Chapter 8" (spec §3, line 2). */
@Composable
private fun conflictChapterLine(position: PositionDomainModel): String? {
    val title = position.locatorTitle?.takeIf { it.isNotBlank() }
    val chapterNumber = position.chapterIndex?.plus(1)
    return when {
        chapterNumber != null && !title.isNullOrBlank() ->
            stringResource(StringRes.position_conflict_chapter_labelled, chapterNumber, title)
        chapterNumber != null ->
            stringResource(StringRes.position_conflict_chapter_only, chapterNumber)
        else -> title
    }
}

/** Spoken line 1: "78 percent", never "78%" (§Accessibility). */
@Composable
fun positionPercentSpoken(percent: Int): String =
    stringResource(StringRes.position_conflict_percent_word, percent)

/** Whole-book percent for cards: progression by heart, audiobook whole-book time as fallback. */
fun displayPercentOf(position: PositionDomainModel): Int = (
    position.totalProgression
        ?: position.progression
        ?: position.bookTimeMs?.let { bookTime ->
            position.totalDurationMs?.takeIf { duration -> duration > 0L }
                ?.let { duration -> bookTime.toDouble() / duration }
        }
        ?: 0.0
    ).coerceIn(0.0, 1.0).let { percent -> (percent * 100.0).roundToLong().toInt() }

/** When the position's real reading happened, in epoch milliseconds; null when missing. */
fun positionObservedMillis(position: PositionDomainModel): Long? =
    position.observedAt?.let { ObservedTime.toEpochMillis(it) }
