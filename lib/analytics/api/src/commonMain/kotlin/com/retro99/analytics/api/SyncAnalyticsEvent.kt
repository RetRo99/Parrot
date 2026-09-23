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
        val routineDirtyWaitMs: Long? = null,
        val routineIntervalMs: Long? = null,
        val routineForcedByMaximumWait: Boolean? = null,
    ) : SyncAnalyticsEvent {
        override val name: String = "sync_run_completed"
        override val parameters: Map<String, Any> = buildMap {
            put("trigger", trigger)
            put("urgency", urgency)
            put("result", result)
            put("duration_ms", durationMs)
            put("pushed_mutation_count", pushedMutationCount)
            put("pulled_change_count", pulledChangeCount)
            put("pending_mutation_count", pendingMutationCount)
            put("destination_count", destinationCount)
            routineDirtyWaitMs?.let { put("routine_dirty_wait_ms", it) }
            routineIntervalMs?.let { put("routine_interval_ms", it) }
            routineForcedByMaximumWait?.let { put("routine_forced_by_max_wait", it) }
        }
    }
}
