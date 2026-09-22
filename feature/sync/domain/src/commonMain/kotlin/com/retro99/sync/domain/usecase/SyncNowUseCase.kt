package com.retro99.sync.domain.usecase

import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class SyncNowUseCase(
    @Provided private val syncRepository: SyncRepository,
) {
    suspend operator fun invoke(
        request: SyncRequest = SyncRequest(
            reason = SyncTriggerReason.MANUAL,
            urgency = SyncUrgency.URGENT,
        ),
    ): SyncResult {
        return syncRepository.requestSync(
            request,
        )
    }
}
