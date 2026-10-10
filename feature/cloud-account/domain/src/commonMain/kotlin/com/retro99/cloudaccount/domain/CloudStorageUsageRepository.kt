package com.retro99.cloudaccount.domain

data class CloudStorageUsage(
    val usedBytes: Long,
    val reservedBytes: Long,
    val quotaBytes: Long,
    val availableBytes: Long,
    /**
     * The breakdown of [usedBytes], when the server sends it. Both are null
     * against a server that does not know about prepared audio yet, and the
     * screen then shows the total alone exactly as it did before.
     */
    val booksBytes: Long? = null,
    val preparedAudioBytes: Long? = null,
)

interface CloudStorageUsageRepository {
    suspend fun getStorageUsage(): CloudStorageUsage
}
