package com.retro99.analytics.implementation

/**
 * Applies a fail-closed schema at the analytics provider boundary. Event names still measure
 * feature use when an event contains no safe parameters. Any new dimensions must be added here
 * deliberately, after verifying that callers only supply bounded, non-user-authored values.
 */
internal fun sanitizeAnalyticsParameters(parameters: Map<String, Any>): Map<String, Any> = buildMap {
    parameters.forEach { (key, value) ->
        when {
            key in SAFE_STRING_KEYS && value is String && SAFE_DIMENSION.matches(value) ->
                put(key, value)

            key in SAFE_BOOLEAN_KEYS && value is Boolean ->
                put(key, value)

            key == "progress_percent" && value is Int && value in 0..100 ->
                put(key, value)

            key == "status_code" && value is Int && value in 0..MAX_HTTP_STATUS_CODE ->
                put(key, value)

            key in SAFE_LONG_KEYS && value is Long && value in 0..MAX_SAFE_LONG ->
                put(key, value)

            key in SAFE_FLOAT_KEYS && value is Float && value.isFinite() && value in 0f..MAX_SAFE_FLOAT ->
                put(key, value)
        }
    }
}

private val SAFE_DIMENSION = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

private val SAFE_STRING_KEYS = setOf(
    "screen",
    "source_screen",
    "entry_point",
    "action",
    "operation",
    "stage",
    "outcome",
    "reason_code",
    "book_type",
    "source",
    "setting_name",
    "section_name",
    "direction",
    "error_type",
    "step",
    "tab_name",
    "server_type",
    "period",
    "detail_type",
    "filter",
    "sort_config",
    "view_mode",
)

private val SAFE_BOOLEAN_KEYS = setOf(
    "is_enabled",
    "is_success",
    "is_favorite",
    "is_neural",
    "is_timeout",
    "is_connectivity",
)

private val SAFE_LONG_KEYS = setOf(
    "reading_duration_ms",
    "duration_ms",
    "download_duration_ms",
)

private val SAFE_FLOAT_KEYS = setOf(
    "rate",
    "pitch",
)

private const val MAX_HTTP_STATUS_CODE = 599
private const val MAX_SAFE_LONG = 31_536_000_000L // one year; reject malformed/unbounded durations
private const val MAX_SAFE_FLOAT = 4f
