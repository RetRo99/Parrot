package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ObserveCloudAuthStateUseCase(
    @Provided private val repository: CloudAccountRepository,
) {
    operator fun invoke(): Flow<CloudAuthState> = repository.observeAuthState()
}
