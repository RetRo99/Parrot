package com.retro99.analytics.api

/** Privacy-safe operational metrics for shared synchronization runs. */
sealed interface SyncAnalyticsEvent : AnalyticsEvent {
    data class RunCompleted(
        val trigger: String,
        val urgency: String,
        val result: String,
        val durationMs: Long,
        val pushedMutationCount: Int,
        val pulledChangeCount: Int,
        val pendingMutationCount: Int,
        val destinationCount: Int,
    ) : SyncAnalyticsEvent {
        override val name: String = "sync_run_completed"
        override val parameters: Map<String, Any> = mapOf(
            "trigger" to trigger,
            "urgency" to urgency,
            "result" to result,
            "duration_ms" to durationMs,
            "pushed_mutation_count" to pushedMutationCount,
            "pulled_change_count" to pulledChangeCount,
            "pending_mutation_count" to pendingMutationCount,
            "destination_count" to destinationCount,
        )
    }
}
