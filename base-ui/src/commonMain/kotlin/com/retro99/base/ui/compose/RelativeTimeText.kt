package com.retro99.base.ui.compose

import androidx.compose.runtime.Composable
import com.retro99.base.nowMillis
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_bookmark_days_ago
import resources.translations.reader_bookmark_hours_ago
import resources.translations.reader_bookmark_just_now
import resources.translations.reader_bookmark_minutes_ago

/** "just now", "5 min ago", "2 hr ago" or "3 days ago", the way bookmarks show their age. */
@Composable
fun relativeTimeText(epochMillis: Long, nowEpochMillis: Long = nowMillis()): String {
    val minutes = ((nowEpochMillis - epochMillis) / MILLIS_PER_MINUTE).coerceAtLeast(0L)
    val hours = minutes / MINUTES_PER_HOUR
    return when {
        minutes < 1 -> stringResource(StringRes.reader_bookmark_just_now)
        minutes < MINUTES_PER_HOUR ->
            stringResource(StringRes.reader_bookmark_minutes_ago, minutes.toInt())
        hours < HOURS_PER_DAY -> stringResource(StringRes.reader_bookmark_hours_ago, hours.toInt())
        else -> stringResource(StringRes.reader_bookmark_days_ago, (hours / HOURS_PER_DAY).toInt())
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
