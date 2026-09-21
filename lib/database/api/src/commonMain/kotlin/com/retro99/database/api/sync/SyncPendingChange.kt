package com.retro99.database.api.sync

data class SyncPendingChange(
    val cloudUserId: String,
    val changeId: Long,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val revision: Long,
    val createdAt: String,
)
