package com.retro99.library.domain.operation

import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationTarget
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class RemoveLibraryDeviceReplicaUseCase(
    @Provided private val repository: LibraryReplicaRemovalRepository,
) {
    suspend operator fun invoke(
        groupId: LibraryGroupId,
        request: LibraryOperationRequest,
        requestedAt: String,
    ): LibraryReplicaRemovalResult {
        require(request.operation == LibraryOperation.RemoveDeviceReplica)
        require(request.target is LibraryOperationTarget.DeviceReplica)
        return repository.remove(groupId, request, requestedAt)
    }
}
