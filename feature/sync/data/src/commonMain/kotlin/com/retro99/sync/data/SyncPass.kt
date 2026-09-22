package com.retro99.sync.data

import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult

/**
 * Backend-specific bounded synchronization pass.
 *
 * The pass is intentionally narrower than [com.retro99.sync.domain.SyncRepository].
 * [SyncDataRepository] owns public request coordination and single-flight
 * behavior; a destination pass only performs one pinned execution.
 */
interface SyncPass {
    suspend fun execute(
        request: SyncRequest,
        context: SyncExecutionContext,
    ): SyncResult
}
