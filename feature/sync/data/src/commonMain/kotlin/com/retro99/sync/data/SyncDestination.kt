package com.retro99.sync.data

import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncPhaseReporter

/**
 * A destination that can participate in the application-wide sync request.
 *
 * A destination returns null when it has no configured/authenticated target
 * for the current profile. This lets the coordinator preserve the result of
 * the existing cloud pass when optional server destinations are unavailable.
 */
interface SyncDestination {
    suspend fun execute(
        request: SyncRequest,
        reportPhase: SyncPhaseReporter,
    ): SyncResult?
}
