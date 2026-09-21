package com.retro99.sync.domain.usecase

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncResult
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class SyncNowUseCase(
    @Provided private val syncRepository: SyncRepository,
) {
    suspend operator fun invoke(): SyncResult {
        return syncRepository.sync()
    }
}
