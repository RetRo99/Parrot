package com.retro99.analytics.api

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

enum class UsageOperation(val eventName: String) {
    Import("book_import_operation"), Link("book_link_operation"),
    LinkedResume("linked_resume_decision"), Conflict("position_conflict_resolution"),
    ApplyPosition("copy_position_apply_operation"),
}

enum class UsageAction(val value: String) {
    Import("import"), ManualLink("manual_link"), SuggestedLink("suggested_link"),
    BulkLink("bulk_link"), Reject("reject"), Skip("skip"), Unlink("unlink"),
    Accept("accept"), Decline("decline"), Local("local"), Remote("remote"),
    Apply("apply"), Shown("shown"), Expanded("expanded"), Collapsed("collapsed"), Dismissed("dismissed"),
}

enum class DiscoveryRoute(val value: String) {
    Library("library"), Search("search"), Favorites("favorites"), Filtered("filtered"),
    Author("author"), Series("series"),
}

enum class DiscoveryDestination(val value: String) { Book("book"), Author("author"), Series("series") }
enum class ReaderNavigationMethod(val value: String) { Toc("toc"), Bookmark("bookmark"), ProgressSlider("progress_slider") }

sealed interface FeatureUsageAnalyticsEvent : AnalyticsEvent {
    data class Operation(
        val operation: UsageOperation,
        val action: UsageAction,
        val screen: String,
        val outcome: ProductOutcome,
        val durationMs: Long = 0,
        val targetCount: Int = 1,
    ) : FeatureUsageAnalyticsEvent {
        override val name = operation.eventName
        override val parameters: Map<String, Any> = mapOf(
            "usage_action" to action.value,
            "screen" to screen,
            "stage" to when {
                action == UsageAction.Shown -> "presented"
                outcome == ProductOutcome.Started -> "started"
                else -> "terminal"
            },
            "outcome" to outcome.value,
            "duration_ms" to durationMs.coerceAtLeast(0),
            "target_count_bucket" to CountBucket.of(targetCount).value,
        )
    }

    /** First combined library result, not a claim that every server has refreshed. */
    data class LibraryLoadCompleted(val outcome: ProductOutcome, val durationMs: Long, val count: Int) : FeatureUsageAnalyticsEvent {
        override val name = "library_load_completed"
        override val parameters: Map<String, Any> = mapOf(
            "outcome" to outcome.value, "duration_ms" to durationMs.coerceAtLeast(0),
            "result_count_bucket" to CountBucket.of(count).value, "load_kind" to "initial_observation",
        )
    }

    data class DiscoverySelected(val route: DiscoveryRoute, val destination: DiscoveryDestination) : FeatureUsageAnalyticsEvent {
        override val name = "library_discovery_selected"
        override val parameters = mapOf("discovery_route" to route.value, "discovery_destination" to destination.value)
    }

    data class RecapInteraction(val action: UsageAction, val isLatest: Boolean) : FeatureUsageAnalyticsEvent {
        override val name = "recap_interaction"
        override val parameters = mapOf("usage_action" to action.value, "is_latest_recap" to isLatest)
    }

    /** Success is emitted only after the reader observes the requested destination. */
    data class NavigationCompleted(val method: ReaderNavigationMethod, val mode: UsageMode, val outcome: ProductOutcome, val durationMs: Long) : FeatureUsageAnalyticsEvent {
        override val name = "reader_navigation_completed"
        override val parameters: Map<String, Any> = mapOf(
            "navigation_method" to method.value, "usage_mode" to mode.value,
            "outcome" to outcome.value, "duration_ms" to durationMs.coerceAtLeast(0),
        )
    }
}

/** Analytics failures must never change the user's action or its result. */
fun Analytics.logFeatureUsage(event: AnalyticsEvent) {
    runCatching { logEvent(event) }
}

/** One terminal result per reader navigation request, even if callbacks repeat. */
class ReaderNavigationTracker(
    private val analytics: Analytics,
    private val elapsedMillis: () -> Long = run {
        val origin = TimeSource.Monotonic.markNow()
        return@run { origin.elapsedNow().inWholeMilliseconds }
    },
) {
    private var method: ReaderNavigationMethod? = null
    private var mode = UsageMode.Reading
    private var startedAt = 0L

    fun begin(method: ReaderNavigationMethod, mode: UsageMode) {
        complete(ProductOutcome.Cancelled)
        this.method = method
        this.mode = mode
        startedAt = elapsedMillis()
    }

    fun complete(outcome: ProductOutcome) {
        val method = this.method ?: return
        this.method = null
        analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.NavigationCompleted(
            method, mode, outcome, (elapsedMillis() - startedAt).coerceAtLeast(0),
        ))
    }
}

/** One attempt and one terminal, including cancellation and unexpected exceptions. */
suspend fun <T> Analytics.trackUsageOperation(
    operation: UsageOperation,
    action: UsageAction,
    screen: String,
    targetCount: Int = 1,
    outcome: (T) -> ProductOutcome,
    block: suspend () -> T,
): T {
    val started = TimeSource.Monotonic.markNow()
    fun report(result: ProductOutcome) = logFeatureUsage(FeatureUsageAnalyticsEvent.Operation(
        operation, action, screen, result, started.elapsedNow().inWholeMilliseconds, targetCount,
    ))
    report(ProductOutcome.Started)
    val result = try {
        block()
    } catch (exception: CancellationException) {
        report(ProductOutcome.Cancelled)
        throw exception
    } catch (exception: Exception) {
        report(ProductOutcome.Failed)
        throw exception
    }
    report(outcome(result))
    return result
}
