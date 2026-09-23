package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudStorageUsage
import com.retro99.cloudaccount.domain.CloudStorageUsageRepository
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class GetCloudStorageUsageUseCase(
    @Provided private val repository: CloudStorageUsageRepository,
) {
    suspend operator fun invoke(): CloudStorageUsage = repository.getStorageUsage()
}
