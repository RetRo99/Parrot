package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class RestoreCloudSessionUseCase(
    @Provided private val repository: CloudAccountRepository,
) {
    suspend operator fun invoke() {
        repository.restoreSession()
    }
}
