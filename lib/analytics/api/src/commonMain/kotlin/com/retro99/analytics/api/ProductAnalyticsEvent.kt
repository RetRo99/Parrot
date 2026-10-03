package com.retro99.analytics.api

/** Product telemetry contains categories and durations, never book identity or search text. */
enum class UsageMode(val value: String) {
    Reading("reading"), Audiobook("audiobook"), ReadAloud("readaloud"), Tts("tts"),
}

enum class SearchScope(val value: String) { Library("library"), Book("book") }
enum class UsageFeature(val value: String) {
    Tts("tts"), Bookmarks("bookmarks"), SleepTimer("sleep_timer"), Backup("backup"),
    Recaps("recaps"), LinkedCopies("linked_copies"), Positions("positions"), ReadAloud("readaloud"),
    LocalImport("local_import"), AuthorBrowsing("author_browsing"), SeriesBrowsing("series_browsing"),
}
enum class UsageEndReason(val value: String) {
    Checkpoint("checkpoint"), Background("background"), Closed("closed"),
    Cleared("cleared"), BookChanged("book_changed"), Paused("paused"),
    Completed("completed"), Error("error"), SourceChanged("source_changed"),
}
enum class ProductOutcome(val value: String) {
    Started("started"), Succeeded("succeeded"), Failed("failed"), Cancelled("cancelled"),
    Queued("queued"), Partial("partial"), RetryScheduled("retry_scheduled"),
}
enum class BackupErrorCategory(val value: String) {
    Rejected("rejected"), TransferFailed("transfer_failed"), QueueFailed("queue_failed"),
    AuthenticationUnavailable("authentication_unavailable"),
}
enum class CountBucket(val value: String) {
    Zero("zero"), One("one"), Few("two_to_five"), Several("six_to_twenty"), Many("over_twenty");

    companion object {
        fun of(count: Int): CountBucket = when {
            count <= 0 -> Zero
            count == 1 -> One
            count <= 5 -> Few
            count <= 20 -> Several
            else -> Many
        }
    }
}

sealed interface ProductAnalyticsEvent : AnalyticsEvent {
    data class ReadingSessionSummary(
        val readingMs: Long,
        val audiobookMs: Long,
        val readAloudMs: Long,
        val ttsMs: Long,
        val foregroundMs: Long,
        val backgroundMs: Long,
        val endReason: UsageEndReason,
    ) : ProductAnalyticsEvent {
        override val name = "reading_session_summary"
        override val parameters = mapOf(
            "reading_duration_ms" to readingMs,
            "audiobook_duration_ms" to audiobookMs,
            "readaloud_duration_ms" to readAloudMs,
            "tts_duration_ms" to ttsMs,
            "foreground_duration_ms" to foregroundMs,
            "background_duration_ms" to backgroundMs,
            "end_reason" to endReason.value,
            // EPUB and audiobook files are prepared on-device before the reader opens.
            // This is NOT a claim that the device has no network connection.
            "content_access" to "on_device",
        )
    }

    data class FirstMeaningfulSession(val mode: UsageMode, val sinceFirstLaunchMs: Long) : ProductAnalyticsEvent {
        override val name = "first_meaningful_session"
        override val parameters = mapOf(
            "usage_mode" to mode.value,
            "since_first_launch_ms" to sinceFirstLaunchMs,
        )
    }

    data class ReaderOpenCompleted(
        val mediaType: String,
        val entryPoint: String,
        val outcome: ProductOutcome,
        val durationMs: Long,
        val isRetry: Boolean,
        val reasonCode: String? = null,
    ) : ProductAnalyticsEvent {
        override val name = "reader_open_completed"
        override val parameters: Map<String, Any> = buildMap {
            put("media_type", mediaType)
            put("entry_point", entryPoint)
            put("outcome", outcome.value)
            put("duration_ms", durationMs)
            put("is_retry", isRetry)
            reasonCode?.let { put("reason_code", it) }
        }
    }

    data class SearchResultsShown(
        val scope: SearchScope,
        val count: Int,
        val durationMs: Long,
        val hasFilters: Boolean = false,
        val outcome: ProductOutcome = ProductOutcome.Succeeded,
        val isCapped: Boolean = false,
        val isRestricted: Boolean = false,
    ) : ProductAnalyticsEvent {
        override val name = "search_results_shown"
        override val parameters = mapOf(
            "search_scope" to scope.value,
            "result_count_bucket" to CountBucket.of(count).value,
            "duration_ms" to durationMs,
            "has_filters" to hasFilters,
            "outcome" to outcome.value,
            "is_capped" to isCapped,
            "is_restricted" to isRestricted,
        )
    }

    data class SearchResultSelected(val scope: SearchScope, val zeroBasedIndex: Int) : ProductAnalyticsEvent {
        override val name = "search_result_selected"
        override val parameters = mapOf(
            "search_scope" to scope.value,
            "result_position_bucket" to CountBucket.of(zeroBasedIndex + 1).value,
        )
    }

    data class ListeningSourceChanged(val previous: UsageMode, val current: UsageMode) : ProductAnalyticsEvent {
        override val name = "listening_source_changed"
        override val parameters = mapOf(
            "previous_usage_mode" to previous.value,
            "usage_mode" to current.value,
            "outcome" to "succeeded",
        )
    }

    data class PlaybackSessionSummary(
        val mode: UsageMode,
        val playingMs: Long,
        val bufferingMs: Long,
        val interruptions: Int,
        val endReason: UsageEndReason,
    ) : ProductAnalyticsEvent {
        override val name = "playback_session_summary"
        override val parameters = mapOf(
            "usage_mode" to mode.value,
            "playing_duration_ms" to playingMs,
            "buffering_duration_ms" to bufferingMs,
            "interruption_count_bucket" to CountBucket.of(interruptions).value,
            "end_reason" to endReason.value,
        )
    }

    data class BookCompleted(val mode: UsageMode) : ProductAnalyticsEvent {
        override val name = "book_completed"
        override val parameters = mapOf("usage_mode" to mode.value, "completion_method" to "automatic")
    }

    data class FeatureExposed(val feature: UsageFeature, val screen: String, val available: Boolean) : ProductAnalyticsEvent {
        override val name = "feature_exposed"
        override val parameters = mapOf(
            "feature_name" to feature.value,
            "screen" to screen,
            "is_available" to available,
        )
    }

    data class BookBackupOperation(
        val bulk: Boolean,
        val stage: String,
        val outcome: ProductOutcome,
        val durationMs: Long,
        val queuedCount: Int = 0,
        val failedCount: Int = 0,
        val isRetry: Boolean = false,
        val errorCategory: BackupErrorCategory? = null,
    ) : ProductAnalyticsEvent {
        override val name = "book_backup_operation"
        override val parameters: Map<String, Any> = buildMap {
            put("backup_scope", if (bulk) "bulk" else "single")
            put("stage", stage)
            put("outcome", outcome.value)
            put("duration_ms", durationMs)
            put("queued_count_bucket", CountBucket.of(queuedCount).value)
            put("failed_count_bucket", CountBucket.of(failedCount).value)
            put("is_retry", isRetry)
            errorCategory?.let { put("backup_error_category", it.value) }
        }
    }
}
