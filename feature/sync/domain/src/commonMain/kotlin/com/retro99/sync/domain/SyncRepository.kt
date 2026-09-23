package com.retro99.sync.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SyncRepository {
    suspend fun sync(): SyncResult

    fun observeStatus(): Flow<SyncStatus> = flowOf(SyncStatus.Idle)

    /**
     * Requests work through the shared synchronization boundary.
     *
     * Compatibility callers still enter the shared bounded pass through this
     * method.
     */
    suspend fun requestSync(request: SyncRequest): SyncResult = sync()
}
