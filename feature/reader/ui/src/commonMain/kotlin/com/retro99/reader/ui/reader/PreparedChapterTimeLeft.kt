package com.retro99.reader.ui.reader

/** The time left as a person says it, rounded exactly as the estimate before the press is. */
internal sealed interface PreparedTimeLeftLabel {
    data object UnderMinute : PreparedTimeLeftLabel
    data class Minutes(val minutes: Int) : PreparedTimeLeftLabel
    data class Hours(val hours: Int, val minutes: Int) : PreparedTimeLeftLabel
}

/** Null when there is no estimate yet, and then nothing about time is said. */
internal fun preparedTimeLeftLabel(remainingMs: Long?): PreparedTimeLeftLabel? {
    if (remainingMs == null) return null
    val minutes = roundedMinutes(remainingMs.coerceAtLeast(0L))
    return when {
        minutes == 0 -> PreparedTimeLeftLabel.UnderMinute
        minutes >= MINUTES_PER_HOUR -> PreparedTimeLeftLabel.Hours(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
        else -> PreparedTimeLeftLabel.Minutes(minutes)
    }
}

private const val MINUTES_PER_HOUR = 60
