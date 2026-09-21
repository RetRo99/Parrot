package com.retro99.sync.domain

interface SyncRepository {
    suspend fun sync(): SyncResult
}
