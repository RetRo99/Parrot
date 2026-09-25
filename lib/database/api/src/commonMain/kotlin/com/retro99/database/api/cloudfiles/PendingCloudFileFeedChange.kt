package com.retro99.database.api.cloudfiles

/** A Cloud file event retained until its metadata row becomes available locally. */
data class PendingCloudFileFeedChange(
    val id: Long,
    val cloudBookId: String,
    val feedRevision: Long?,
    val payloadJson: String,
    val receivedAt: String,
)
