package com.retro99.cloudaccount.data

import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.cloudaccount.domain.CloudStorageUsage
import com.retro99.cloudaccount.domain.CloudStorageUsageRepository
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CloudStorageUsageRepository::class])
class SupabaseCloudStorageUsageRepository(
    @Provided private val clientProvider: SupabaseClientProvider,
) : CloudStorageUsageRepository {
    override suspend fun getStorageUsage(): CloudStorageUsage {
        val result = clientProvider.client.postgrest
            .rpc("get_storage_usage")
            .decodeAs<StorageUsageResponse>()
        return CloudStorageUsage(
            usedBytes = result.usedBytes,
            reservedBytes = result.reservedBytes,
            quotaBytes = result.quotaBytes,
            availableBytes = result.availableBytes,
        )
    }
}

@Serializable
private data class StorageUsageResponse(
    @SerialName("used_bytes") val usedBytes: Long,
    @SerialName("reserved_bytes") val reservedBytes: Long,
    @SerialName("quota_bytes") val quotaBytes: Long,
    @SerialName("available_bytes") val availableBytes: Long,
)
