package com.retro99.analytics.implementation

/**
 * Applies a fail-closed schema at the analytics provider boundary. Event names still measure
 * feature use when an event contains no safe parameters. Any new dimensions must be added here
 * deliberately, after verifying that callers only supply bounded, non-user-authored values.
 */
internal fun sanitizeAnalyticsParameters(parameters: Map<String, Any>): Map<String, Any> = buildMap {
    val settingName = (parameters["setting_name"] as? String)
        ?.takeIf { it in SAFE_SETTING_NAMES }
    parameters.forEach { (key, value) ->
        when {
            key in PRODUCT_ENUM_DIMENSIONS && value is String && value in PRODUCT_ENUM_DIMENSIONS.getValue(key) ->
                put(key, value)

            key == "setting_name" && value is String && value in SAFE_SETTING_NAMES ->
                put(key, value)

            key == "new_value" && value is String && settingName != null ->
                normalizeSettingValueBucket(settingName, value)?.let { put("value_bucket", it) }

            key == "value_bucket" && value is String && value in SAFE_SETTING_BUCKETS ->
                put(key, value)

            key == "tts_action" && value is String && value in SAFE_TTS_ACTIONS ->
                put(key, value)

            key == "tts_outcome" && value is String && value in SAFE_TTS_OUTCOMES ->
                put(key, value)

            key == "tts_reason_code" && value is String && value in SAFE_TTS_REASON_CODES ->
                put(key, value)

            key == "sort_config" && value is String && value in SAFE_BOOK_SORT_CONFIGS ->
                put(key, value)

            key == "view_mode" && value is String && value in SAFE_BOOK_VIEW_MODES ->
                put(key, value)

            key in SAFE_SERVER_TYPE_KEYS && value is String && value in SAFE_SERVER_TYPES ->
                put(key, value)

            key == "field" && value is String && value in SAFE_VALIDATION_FIELDS ->
                put(key, value)

            key == "consent_kind" && value is String && value in SAFE_CLOUD_ACCOUNT_CONSENT_KINDS ->
                put(key, value)

            key == "observation" && value is String && value in SAFE_CLOUD_ACCOUNT_OBSERVATIONS ->
                put(key, value)

            key == "bubble_side" && value is String && value in SAFE_BUBBLE_SIDES ->
                put(key, value)

            key == "media_type" && value is String && value in SAFE_MEDIA_TYPES ->
                put(key, value)

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

/** Converts setting values to bounded categories; raw values never cross the provider boundary. */
internal fun normalizeSettingValueBucket(settingName: String, value: String): String? {
    if (settingName !in SAFE_SETTING_NAMES) return null
    val normalized = value.lowercase()
    return when (settingName) {
        "theme" -> normalized.takeIf { it in setOf("light", "dark", "sepia", "system") }
        "font_family" -> when (normalized) {
            "default", "serif", "sans-serif", "cursive", "fantasy", "monospace",
            "accessibledfa", "ia writer duospace", "opendyslexic" -> "built_in"
            else -> "custom"
        }
        "text_align" -> normalized.takeIf { it in setOf("start", "end", "center", "justify") }
        "line_height", "paragraph_spacing", "margin_horizontal", "margin_vertical" ->
            value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let {
                when {
                    it < 1.0 -> "low"
                    it < 4.0 -> "mid"
                    else -> "high"
                }
            }
        "chapter_progress_display_mode" -> normalized.takeIf { it in setOf("none", "percentage", "relative", "fixed") }
        "progress_indicator_mode" -> normalized.takeIf { it in setOf("none", "chapter", "book") }
        "progress_bar_position" -> normalized.takeIf { it in setOf("top", "bottom") }
        "highlight_style" -> when (normalized) {
            "highlight" -> "highlight"
            "underline" -> "underline"
            "highlight_underline" -> "both"
            else -> null
        }
        "volume_up_action", "volume_down_action", "left_tap_action", "right_tap_action" ->
            when (normalized) {
                "next_page" -> "next_page"
                "previous_page" -> "previous_page"
                else -> null
            }
        "highlight_color_argb", "underline_color_argb" -> value.toLongOrNull()?.let(::colorBucket)
        "font_size" -> value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 6.0..96.0 }?.let {
            when {
                it < 14.0 -> "extra_small"
                it < 18.0 -> "small"
                it < 26.0 -> "medium"
                it < 36.0 -> "large"
                else -> "extra_large"
            }
        }
        "font_weight" -> value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let {
            when {
                it < 0.25 -> "light"
                it < 0.45 -> "regular"
                it < 0.65 -> "medium"
                else -> "bold"
            }
        }
        "double_tap_timeout_ms" -> value.toLongOrNull()?.takeIf { it in 100..2_000 }?.let {
            when {
                it < 300 -> "short"
                it <= 600 -> "normal"
                else -> "long"
            }
        }
        "playback_speed" -> value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.5..2.0 }?.let {
            when {
                it < 1.0 -> "slower"
                it == 1.0 -> "normal"
                else -> "faster"
            }
        }
        else -> when (normalized) {
            "true" -> "enabled"
            "false" -> "disabled"
            "null" -> "system"
            else -> null
        }
    }
}

private fun colorBucket(encoded: Long): String {
    val color = encoded.toInt()
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color and 0xFF
    val luminance = (red * 299 + green * 587 + blue * 114) / 1000
    return when {
        luminance < 64 -> "dark"
        luminance < 160 -> "mid"
        else -> "light"
    }
}

private val SAFE_DIMENSION = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

private val SAFE_STRING_KEYS = setOf(
    "screen",
    "source_screen",
    "destination_screen",
    "entry_point",
    "action",
    "operation",
    "stage",
    "outcome",
    "reason_code",
    "result_code",
    "book_type",
    "source",
    "section_name",
    "direction",
    "error_type",
    "auth_method",
    "mode",
    "step",
    "tab_name",
    "source_tab",
    "destination_tab",
    "period",
    "detail_type",
    "filter",
)

private val SAFE_SERVER_TYPE_KEYS = setOf("server_type", "previous_server_type")
private val SAFE_SERVER_TYPES = setOf("storyteller", "audiobookshelf", "parrot-cloud", "local", "unknown")
private val SAFE_VALIDATION_FIELDS = setOf("server_url", "required_fields", "email")
private val SAFE_CLOUD_ACCOUNT_CONSENT_KINDS = setOf("account_terms", "upload_rights")
private val SAFE_CLOUD_ACCOUNT_OBSERVATIONS = setOf("auth_state", "sync_status")

private val SAFE_SETTING_NAMES = setOf(
    "file_logging", "crash_only_logging", "open_last_book_on_launch", "show_continue_reading",
    "theme", "font_size", "font_family", "font_weight", "text_normalization", "line_height",
    "paragraph_spacing", "margin_horizontal", "margin_vertical", "text_align", "scroll_mode",
    "publisher_styles", "show_progress_bar", "chapter_progress_display_mode", "show_total_progress",
    "progress_indicator_mode", "progress_bar_position", "highlight_color_argb", "underline_color_argb",
    "highlight_style", "fullscreen_mode", "show_current_time", "show_reading_time",
    "volume_buttons_enabled", "volume_up_action", "volume_down_action", "tap_navigation_enabled",
    "left_tap_action", "right_tap_action", "double_tap_timeout_ms", "show_audio_progress_bar",
    "keep_screen_on_during_audio", "tts_enabled", "playback_speed",
)

private val SAFE_SETTING_BUCKETS = setOf(
    "light", "dark", "sepia", "system", "built_in", "custom", "start", "end", "center", "justify",
    "none", "percentage", "relative", "fixed", "chapter", "book", "top", "bottom", "highlight",
    "underline", "both", "next_page", "previous_page", "extra_small", "small", "medium", "large",
    "extra_large", "short", "normal", "long", "slower", "faster", "enabled", "disabled", "low",
    "mid", "high",
)

private val SAFE_BOOK_SORT_CONFIGS = setOf(
    "title_ascending",
    "title_descending",
    "author_ascending",
    "author_descending",
    "rating_ascending",
    "rating_descending",
    "date_published_ascending",
    "date_published_descending",
    "date_added_ascending",
    "date_added_descending",
)

private val SAFE_BOOK_VIEW_MODES = setOf("list", "grid")
private val SAFE_BUBBLE_SIDES = setOf("start", "end")
private val SAFE_MEDIA_TYPES = setOf("ebook", "audiobook", "readaloud")
private val SAFE_TTS_ACTIONS = setOf(
    "controls", "sentence_tap", "chapter", "resume", "preview_resume", "active_playback",
)
private val SAFE_TTS_OUTCOMES = setOf("attempted", "succeeded", "failed", "cancelled")
private val SAFE_TTS_REASON_CODES = setOf(
    "permission_denied", "content_unavailable", "chapter_unavailable", "synthesis_failed",
    "player_unavailable", "player_error", "start_timeout", "operation_cancelled", "unexpected_error",
)

private val SAFE_BOOLEAN_KEYS = setOf(
    "has_filters", "is_capped", "is_restricted", "is_available",
    "is_enabled",
    "is_success",
    "is_retry",
    "is_favorite",
    "is_neural",
    "is_timeout",
    "is_connectivity",
    "is_visible",
)

private val SAFE_LONG_KEYS = setOf(
    "audiobook_duration_ms", "readaloud_duration_ms", "tts_duration_ms",
    "foreground_duration_ms", "background_duration_ms", "playing_duration_ms",
    "buffering_duration_ms", "since_first_launch_ms",
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

private val PRODUCT_ENUM_DIMENSIONS = mapOf(
    "usage_mode" to setOf("reading", "audiobook", "readaloud", "tts"),
    "previous_usage_mode" to setOf("reading", "audiobook", "readaloud", "tts"),
    "search_scope" to setOf("library", "book"),
    "feature_name" to setOf("tts", "bookmarks", "sleep_timer", "backup"),
    "end_reason" to setOf("checkpoint", "background", "closed", "cleared", "book_changed", "paused", "completed", "error", "source_changed"),
    "content_access" to setOf("on_device"),
    "completion_method" to setOf("automatic"),
    "backup_scope" to setOf("single", "bulk"),
    "backup_error_category" to setOf("rejected", "transfer_failed", "queue_failed", "authentication_unavailable"),
) + listOf("result_count_bucket", "result_position_bucket", "interruption_count_bucket", "queued_count_bucket", "failed_count_bucket")
    .associateWith { setOf("zero", "one", "two_to_five", "six_to_twenty", "over_twenty") }
