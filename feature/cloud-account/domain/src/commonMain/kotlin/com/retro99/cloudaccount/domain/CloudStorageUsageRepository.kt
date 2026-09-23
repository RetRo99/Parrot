package com.retro99.cloudaccount.domain

data class CloudStorageUsage(
    val usedBytes: Long,
    val reservedBytes: Long,
    val quotaBytes: Long,
    val availableBytes: Long,
)

interface CloudStorageUsageRepository {
    suspend fun getStorageUsage(): CloudStorageUsage
}
