package com.retro99.library.domain.operation

import com.retro99.server.api.library.LibraryProfileId
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class RecoverLibraryDeviceReplicaRemovalsUseCase(
    @Provided private val repository: LibraryReplicaRemovalRepository,
) {
    suspend operator fun invoke(
        profileId: LibraryProfileId,
    ): List<LibraryReplicaRemovalResult> = repository.recoverPending(profileId)
}
