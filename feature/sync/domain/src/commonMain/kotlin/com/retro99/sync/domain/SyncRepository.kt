package com.retro99.sync.domain

interface SyncRepository {
    suspend fun sync(): SyncResult

    /**
     * Requests work through the shared synchronization boundary.
     *
     * Existing adapters fall back to their legacy bounded pass until the
     * shared engine is installed.
     */
    suspend fun requestSync(request: SyncRequest): SyncResult = sync()
}
