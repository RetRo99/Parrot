package com.retro99.analytics.implementation

import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.BackupErrorCategory
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.SearchScope
import com.retro99.analytics.api.UsageEndReason
import com.retro99.analytics.api.UsageFeature
import com.retro99.analytics.api.UsageMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductAnalyticsSchemaTest {
    @Test
    fun allTenEventsSurviveProviderSanitization() {
        val events = listOf(
            ProductAnalyticsEvent.ReadingSessionSummary(1L, 2L, 3L, 4L, 5L, 5L, UsageEndReason.Closed),
            ProductAnalyticsEvent.FirstMeaningfulSession(UsageMode.Tts, 120_000L),
            ProductAnalyticsEvent.ReaderOpenCompleted("ebook", "book_detail", ProductOutcome.Failed, 20L, false, "publication_open_failed"),
            ProductAnalyticsEvent.SearchResultsShown(SearchScope.Library, 12, 30L, hasFilters = true),
            ProductAnalyticsEvent.SearchResultSelected(SearchScope.Book, 0),
            ProductAnalyticsEvent.ListeningSourceChanged(UsageMode.ReadAloud, UsageMode.Tts),
            ProductAnalyticsEvent.PlaybackSessionSummary(UsageMode.Audiobook, 10L, 5L, 2, UsageEndReason.Paused),
            ProductAnalyticsEvent.BookCompleted(UsageMode.Reading),
            ProductAnalyticsEvent.FeatureExposed(UsageFeature.Backup, "books_library", true),
            ProductAnalyticsEvent.BookBackupOperation(true, "queue", ProductOutcome.Partial, 10L, 3, 1, true, BackupErrorCategory.QueueFailed),
        )
        assertEquals(10, events.map { it.name }.distinct().size)
        events.forEach { event ->
            assertEquals(event.parameters, sanitizeAnalyticsParameters(event.parameters), event.name)
        }
    }

    @Test
    fun rejectsPrivateValuesEvenUnderProductDimensionKeys() {
        assertEquals(emptyMap(), sanitizeAnalyticsParameters(mapOf(
            "usage_mode" to "private_book", "previous_usage_mode" to "private_voice",
            "search_scope" to "private_query", "feature_name" to "private_feature",
            "content_access" to "https://private.server", "end_reason" to "private_exception",
            "completion_method" to "private_title", "backup_scope" to "private_path",
            "backup_error_category" to "private_server_reason",
            "result_count_bucket" to "private_query", "result_position_bucket" to "private_book",
            "queued_count_bucket" to "private_count", "failed_count_bucket" to "private_error",
            "interruption_count_bucket" to "private_count", "query" to "private_text",
            "playing_duration_ms" to -1L, "buffering_duration_ms" to Long.MAX_VALUE,
        )))
    }
}
