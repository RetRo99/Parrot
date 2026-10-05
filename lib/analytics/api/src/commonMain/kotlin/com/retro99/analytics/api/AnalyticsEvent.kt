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

    data class ReaderSettingSaveAttempted(
        val settingName: String,
        val isRetry: Boolean,
        val isUndo: Boolean = false,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_setting_save_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "action" to if (isUndo) "undo_setting_change" else "save_setting",
            "operation" to if (isUndo) "reader_setting_undo" else "reader_setting_save",
            "stage" to "started",
            "outcome" to "started",
            "setting_name" to settingName,
            "is_retry" to isRetry,
        )
    }

    data class ReaderSettingSaveSucceeded(
        val settingName: String,
        val isRetry: Boolean,
        val isUndo: Boolean = false,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_setting_save_succeeded"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "action" to if (isUndo) "undo_setting_change" else "save_setting",
            "operation" to if (isUndo) "reader_setting_undo" else "reader_setting_save",
            "stage" to "terminal",
            "outcome" to "succeeded",
            "setting_name" to settingName,
            "is_retry" to isRetry,
        )
    }

    data class ReaderSettingSaveFailed(
        val settingName: String,
        val reasonCode: String,
        val isRetry: Boolean,
        val isUndo: Boolean = false,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_setting_save_failed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "action" to if (isUndo) "undo_setting_change" else "save_setting",
            "operation" to if (isUndo) "reader_setting_undo" else "reader_setting_save",
            "stage" to "terminal",
            "outcome" to "failed",
            "reason_code" to reasonCode,
            "setting_name" to settingName,
            "is_retry" to isRetry,
        )
    }

    data class ReaderSettingSaveCancelled(
        val settingName: String,
        val isRetry: Boolean,
        val isUndo: Boolean = false,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_setting_save_cancelled"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "action" to if (isUndo) "undo_setting_change" else "save_setting",
            "operation" to if (isUndo) "reader_setting_undo" else "reader_setting_save",
            "stage" to "terminal",
            "outcome" to "cancelled",
            "setting_name" to settingName,
            "is_retry" to isRetry,
        )
    }

    data class ReaderSettingSaveAbandoned(
        val settingName: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_setting_save_abandoned"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "action" to "save_setting",
            "operation" to "reader_setting_save",
            "stage" to "recovery",
            "outcome" to "abandoned",
            "setting_name" to settingName,
        )
    }

    data object ReaderSettingChangeUndone : ReaderAnalyticsEvent {
        override val name: String = "reader_setting_change_undone"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "action" to "undo_setting_change",
            "operation" to "reader_setting_undo",
            "stage" to "terminal",
            "outcome" to "reversed",
        )
    }

    data class ReaderSettingsSectionCollapsed(
        val sectionName: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_settings_section_collapsed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "section_name" to sectionName,
        )
    }

    data class ReaderSettingsFontsToggled(
        val isExpanded: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_settings_fonts_toggled"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "is_enabled" to isExpanded,
        )
    }

    data object CustomFontImportCancelled : ReaderAnalyticsEvent {
        override val name: String = "reader_custom_font_import_cancelled"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader_settings",
            "operation" to "custom_font_import",
            "stage" to "terminal",
            "outcome" to "cancelled",
        )
    }

    data class CurrentBookTargetSaveAttempted(
        val entryPoint: String,
        val bookType: String,
        val isRetry: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "current_book_target_save_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "save_current_book_target",
            "operation" to "current_book_target_save",
            "stage" to "started",
            "outcome" to "started",
            "entry_point" to entryPoint,
            "book_type" to bookType,
            "is_retry" to isRetry,
        )
    }

    data class CurrentBookTargetSaveCompleted(
        val entryPoint: String,
        val bookType: String,
        val isRetry: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "current_book_target_save_completed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "save_current_book_target",
            "operation" to "current_book_target_save",
            "stage" to "terminal",
            "outcome" to "succeeded",
            "entry_point" to entryPoint,
            "book_type" to bookType,
            "is_retry" to isRetry,
        )
    }

    data class CurrentBookTargetSaveFailed(
        val entryPoint: String,
        val bookType: String,
        val isRetry: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "current_book_target_save_failed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "save_current_book_target",
            "operation" to "current_book_target_save",
            "stage" to "terminal",
            "outcome" to "failed",
            "reason_code" to "current_book_target_save_failed",
            "entry_point" to entryPoint,
            "book_type" to bookType,
            "is_retry" to isRetry,
        )
    }

    data class ReaderPositionSaveRetryAttempted(
        val mediaType: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_position_save_retry_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "retry_reading_position_save",
            "operation" to "reader_position_save",
            "stage" to "retry",
            "outcome" to "started",
            "media_type" to mediaType,
            "is_retry" to true,
        )
    }

    data class ReaderPositionSaveAttempted(
        val mediaType: String,
        val entryPoint: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_position_save_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "save_reading_position",
            "operation" to "reader_position_save",
            "stage" to "started",
            "outcome" to "started",
            "media_type" to mediaType,
            "entry_point" to entryPoint,
            "is_retry" to false,
        )
    }

    data class ReaderPositionSaveSucceeded(
        val mediaType: String,
        val entryPoint: String,
        val isRetry: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_position_save_succeeded"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "save_reading_position",
            "operation" to "reader_position_save",
            "stage" to "terminal",
            "outcome" to "succeeded",
            "media_type" to mediaType,
            "entry_point" to entryPoint,
            "is_retry" to isRetry,
        )
    }

    data class ReaderPositionSaveFailed(
        val mediaType: String,
        val reasonCode: String,
        val isRetry: Boolean,
        val entryPoint: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_position_save_failed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "save_reading_position",
            "operation" to "reader_position_save",
            "stage" to "terminal",
            "outcome" to "failed",
            "media_type" to mediaType,
            "reason_code" to reasonCode,
            "entry_point" to entryPoint,
            "is_retry" to isRetry,
        )
    }

    data class ReaderPositionSaveRetryCancelled(
        val mediaType: String,
    ) : ReaderAnalyticsEvent {
        override val name: String = "reader_position_save_retry_cancelled"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "reader",
            "action" to "retry_reading_position_save",
            "operation" to "reader_position_save",
            "stage" to "terminal",
            "outcome" to "cancelled",
            "media_type" to mediaType,
            "is_retry" to true,
        )
    }

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
        val isRetry: Boolean = false,
    ) : ReaderAnalyticsEvent {
        override val name: String = "setting_changed"
        override val parameters: Map<String, Any> = mapOf(
            "setting_name" to settingName,
            "new_value" to newValue,
            "is_retry" to isRetry,
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
            "screen" to "reader_settings",
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
     * Tracks a failed dictionary "speak word" synthesis. The only analytics the feature emits:
     * successful words are silent and preview events are never reused.
     */
    data class SpeakWordFailed(
        val voiceId: String,
        val isNeural: Boolean,
    ) : ReaderAnalyticsEvent {
        override val name: String = "speak_word_failed"
        override val parameters: Map<String, Any> = mapOf(
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

    /**
     * Records one TTS playback attempt or terminal result with bounded, non-content dimensions.
     * Voice IDs, book IDs, narration text and exception messages are intentionally excluded.
     */
    data class TtsPlaybackOperation(
        val action: String,
        val outcome: String,
        val isRetry: Boolean,
        val durationMs: Long? = null,
        val reasonCode: String? = null,
    ) : ReaderAnalyticsEvent {
        override val name: String = "tts_playback_operation"
        override val parameters: Map<String, Any> = buildMap {
            put("operation", "tts_playback")
            put("tts_action", action)
            put("tts_outcome", outcome)
            put("is_retry", isRetry)
            put("media_type", "ebook")
            durationMs?.let { put("duration_ms", it) }
            reasonCode?.let { put("tts_reason_code", it) }
        }
    }
}
