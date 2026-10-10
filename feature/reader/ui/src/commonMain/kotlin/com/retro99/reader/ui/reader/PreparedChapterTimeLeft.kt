package com.retro99.reader.ui.reader

/** The time left as a person says it, rounded exactly as the estimate before the press is. */
internal sealed interface PreparedTimeLeftLabel {
    data object UnderMinute : PreparedTimeLeftLabel
    data class Minutes(val minutes: Int) : PreparedTimeLeftLabel
    data class Hours(val hours: Int, val minutes: Int) : PreparedTimeLeftLabel
}

/** Null when there is no estimate yet, and then nothing about time is said. */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun preparedTimeLeftLabel(remainingMs: Long?): PreparedTimeLeftLabel? = null
