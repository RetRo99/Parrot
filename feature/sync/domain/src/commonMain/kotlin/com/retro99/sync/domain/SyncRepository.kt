package com.retro99.sync.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SyncRepository {
    suspend fun sync(): SyncResult

    fun observeStatus(): Flow<SyncStatus> = flowOf(SyncStatus.Idle)

    /**
     * Requests work through the shared synchronization boundary.
     *
     * Existing adapters fall back to their legacy bounded pass until the
     * shared engine is installed.
     */
    suspend fun requestSync(request: SyncRequest): SyncResult = sync()
}
