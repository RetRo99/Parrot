package com.retro99.sync.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * Reads the observation times positions carry. Servers and older code use different forms: an
 * ISO-8601 instant, a zone-less local date-time (this device's zone), or epoch milliseconds
 * (Audiobookshelf's `lastUpdate`).
 */
object ObservedTime {

    fun toEpochMillis(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.all { char -> char.isDigit() }) return text.toLongOrNull()
        Instant.parseOrNull(text)?.let { instant -> return instant.toEpochMilliseconds() }
        return try {
            LocalDateTime.parse(text)
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        } catch (exception: IllegalArgumentException) {
            null
        }
    }

    /** The same moment as an ISO-8601 instant, or the input unchanged when it can't be read. */
    fun normalize(value: String?): String? {
        val millis = toEpochMillis(value) ?: return value
        return Instant.fromEpochMilliseconds(millis).toString()
    }

    fun fromEpochMillis(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()
}
