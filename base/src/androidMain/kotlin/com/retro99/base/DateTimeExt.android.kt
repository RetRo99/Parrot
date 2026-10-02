package com.retro99.base

import java.text.DateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

actual fun languageDisplayName(code: String): String {
    val locale = Locale.forLanguageTag(code.trim().replace('_', '-'))
    return locale.getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() } ?: code
}

/**
 * Android implementation of formatCurrentTime.
 * Uses Java's DateFormat to format time according to the user's locale and
 * 12/24 hour preference set in system settings.
 */
actual fun formatCurrentTime(): String {
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    return timeFormat.format(Date())
}

actual fun formatMediumDate(year: Int, month: Int, day: Int): String {
    val calendar = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, 12, 0, 0)
    }
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(calendar.time)
}
