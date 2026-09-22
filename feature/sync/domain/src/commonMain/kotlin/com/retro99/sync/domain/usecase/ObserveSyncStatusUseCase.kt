package com.retro99.sync.domain.usecase

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncStatus
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ObserveSyncStatusUseCase(
    @Provided private val syncRepository: SyncRepository,
) {
    operator fun invoke(): Flow<SyncStatus> = syncRepository.observeStatus()
}
