package com.retro99.analytics.api

/**
 * Base sealed interface for all analytics events.
 * Each event has a name and optional parameters for Firebase Analytics.
 */
sealed interface AnalyticsEvent {
    val name: String
    val parameters: Map<String, Any>
        get() = emptyMap()
}

/**
 * Reader-related analytics events for tracking feature usage.
 */
sealed interface ReaderAnalyticsEvent : AnalyticsEvent {

    /**
     * Tracks when a book is opened - helps understand which books/types are popular.
     */
    data class BookOpened(
        val bookUuid: String,
        val bookType: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "book_opened"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "book_type" to bookType,
        )
    }

    /**
     * Tracks when a book is closed - helps understand reading session duration.
     */
    data class BookClosed(
        val bookUuid: String,
        val readingDurationMs: Long,
        val progressPercent: Int,
    ) : ReaderAnalyticsEvent {
        override val name: String = "book_closed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "reading_duration_ms" to readingDurationMs,
            "progress_percent" to progressPercent,
        )
    }

    /**
     * Tracks when settings panel is opened - helps understand if settings feature is used.
     */
    data class SettingsOpened(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "settings_opened"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    /**
     * Tracks when table of contents is opened - helps understand if TOC feature is used.
     */
    data class TocOpened(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "toc_opened"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class BookmarksOpened(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "bookmarks_opened"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class BookmarkAdded(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "bookmark_added"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    /**
     * Tracks when audio playback is started - helps understand if audio feature is used.
     */
    data class PlaybackStarted(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "playback_started"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    /**
     * Tracks when a reader setting is changed - helps understand user preferences
     * so we can set better defaults.
     */
    data class SettingChanged(
        val settingName: String,
        val newValue: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "setting_changed"
        override val parameters: Map<String, Any> = mapOf(
            "setting_name" to settingName,
            "new_value" to newValue,
        )
    }

    /**
     * Tracks when a settings section is expanded - helps understand which sections
     * are most used so we can prioritize their position.
     */
    data class SettingsSectionExpanded(
        val sectionName: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "settings_section_expanded"
        override val parameters: Map<String, Any> = mapOf(
            "section_name" to sectionName,
        )
    }

    /**
     * Tracks when a book fails to open - helps identify and fix publication issues.
     */
    data class BookOpenFailed(
        val bookUuid: String,
        val bookType: String,
        val errorMessage: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "book_open_failed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "book_type" to bookType,
            "error_message" to errorMessage,
        )
    }

    /**
     * Tracks when a ReadAloud book is opened but has no media overlays.
     * This indicates a content issue that should be investigated.
     */
    data class ReadAloudMissingMediaOverlays(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "readaloud_missing_media_overlays"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class BookmarkDeleted(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "bookmark_deleted"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class BookmarkRenamed(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "bookmark_renamed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class SleepTimerStarted(
        val bookUuid: String,
        val durationMs: Long,
    ) : ReaderAnalyticsEvent {
        override val name: String = "sleep_timer_started"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "duration_ms" to durationMs,
        )
    }

    data class SleepTimerCancelled(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "sleep_timer_cancelled"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class AudioOnlyModeToggled(
        val bookUuid: String,
        val isEnabled: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "audio_only_mode_toggled"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "is_enabled" to isEnabled,
        )
    }

    data class PlaybackPaused(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "playback_paused"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class SkipForward(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "skip_forward"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class SkipBackward(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "skip_backward"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class ChapterNavigated(
        val bookUuid: String,
        val direction: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "chapter_navigated"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "direction" to direction,
        )
    }

    data class HighlightColorChanged(
        val bookUuid: String,
        val colorArgb: Int,
    ) : ReaderAnalyticsEvent {
        override val name: String = "highlight_color_changed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "color_argb" to colorArgb,
        )
    }

    /**
     * Tracks when the on-device text-to-speech feature is enabled or disabled.
     */
    data class TtsEnabledChanged(
        val isEnabled: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_enabled_changed"
        override val parameters: Map<String, Any> = mapOf(
            "is_enabled" to isEnabled,
        )
    }

    /**
     * Tracks when the user picks a text-to-speech voice.
     */
    data class TtsVoiceSelected(
        val bookUuid: String,
        val voiceId: String,
        val isNeural: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_voice_selected"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "voice_id" to voiceId,
            "is_neural" to isNeural,
        )
    }

    /**
     * Tracks when the user previews a text-to-speech voice.
     */
    data class TtsVoicePreviewed(
        val bookUuid: String,
        val voiceId: String,
        val isNeural: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_voice_previewed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "voice_id" to voiceId,
            "is_neural" to isNeural,
        )
    }

    /**
     * Tracks when the text-to-speech voice settings screen is opened.
     */
    data class TtsVoiceSettingsOpened(
        val bookUuid: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_voice_settings_opened"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    /**
     * Tracks text-to-speech speed changes.
     */
    data class TtsRateChanged(
        val bookUuid: String,
        val rate: Float,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_rate_changed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "rate" to rate,
        )
    }

    /**
     * Tracks text-to-speech pitch changes.
     */
    data class TtsPitchChanged(
        val bookUuid: String,
        val pitch: Float,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_pitch_changed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "pitch" to pitch,
        )
    }

    /**
     * Tracks the on-device neural voice model preparation (download and load).
     */
    data class TtsModelPrepared(
        val isSuccess: Boolean,
        val durationMs: Long,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_model_prepared"
        override val parameters: Map<String, Any> = mapOf(
            "is_success" to isSuccess,
            "duration_ms" to durationMs,
        )
    }
}

