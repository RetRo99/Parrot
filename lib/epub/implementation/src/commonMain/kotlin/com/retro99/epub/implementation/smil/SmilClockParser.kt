package com.retro99.epub.implementation.smil

import com.retro99.analytics.api.Analytics
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Parses SMIL clock values into seconds.
 *
 * Supports both colon-separated formats (HH:MM:SS, MM:SS) and metric formats
 * (e.g., "2.5s", "100ms", "1.5h", "30min").
 *
 * @see <a href="https://www.w3.org/TR/SMIL3/smil-timing.html#Timing-ClockValueSyntax">SMIL Clock Value Syntax</a>
 */
@Single
class SmilClockParser(
    @Provided private val analytics: Analytics,
) {

    /**
     * Parses a SMIL clock value string into seconds.
     *
     * @param value The clock value string (e.g., "2.5s", "00:01:30", "100ms")
     * @return The time in seconds, or null if parsing fails
     */
    fun parse(value: String?): Double? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        return runCatching {
            if (":" in trimmed) {
                parseColonFormat(trimmed)
            } else {
                parseMetricFormat(trimmed)
            }
        }.onFailure { e ->
            analytics.logException(e, "Failed to parse SMIL clock value: '$value'")
        }.getOrNull()
    }

    /**
     * Parses colon-separated clock values.
     *
     * Supports:
     * - MM:SS (e.g., "01:30" = 90 seconds)
     * - HH:MM:SS (e.g., "01:30:00" = 5400 seconds)
     */
    private fun parseColonFormat(value: String): Double {
        val parts = value.split(":").mapNotNull { it.toDoubleOrNull() }
        return when (parts.size) {
            2 -> parts[0] * SECONDS_PER_MINUTE + parts[1]
            3 -> parts[0] * SECONDS_PER_HOUR + parts[1] * SECONDS_PER_MINUTE + parts[2]
            else -> parts.lastOrNull() ?: 0.0
        }
    }

    /**
     * Parses metric clock values with unit suffixes.
     *
     * Supports:
     * - "h" for hours (e.g., "1.5h" = 5400 seconds)
     * - "min" for minutes (e.g., "30min" = 1800 seconds)
     * - "s" for seconds (e.g., "2.5s" = 2.5 seconds)
     * - "ms" for milliseconds (e.g., "100ms" = 0.1 seconds)
     * - No suffix defaults to seconds
     */
    private fun parseMetricFormat(value: String): Double {
        val metricStart = value.indexOfFirst { it.isLetter() }
        val number = if (metricStart == -1) value else value.take(metricStart)
        val count = number.toDoubleOrNull() ?: return 0.0
        val metric = if (metricStart == -1) "" else value.substring(metricStart).lowercase()

        return when (metric) {
            "h" -> count * SECONDS_PER_HOUR
            "min" -> count * SECONDS_PER_MINUTE
            "s", "" -> count
            "ms" -> count / MS_PER_SECOND
            else -> count
        }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60.0
        const val SECONDS_PER_HOUR = 3600.0
        const val MS_PER_SECOND = 1000.0
    }
}

