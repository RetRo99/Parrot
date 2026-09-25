package com.retro99.library.domain.operation

import com.retro99.server.api.library.LibraryBookOperation
import com.retro99.server.api.library.LibraryBookOperationRequest
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationResult
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ExecuteLibraryBookOperationUseCase(
    @Provided private val adapterRegistry: LibraryOperationAdapterRegistry,
) {
    suspend operator fun invoke(
        request: LibraryBookOperationRequest,
    ): LibraryOperationResult {
        if (request.operation == LibraryBookOperation.RemoveRemoteBook &&
            !request.userConfirmed
        ) {
            return LibraryOperationResult.Rejected(
                "Confirm the remote book removal before continuing",
            )
        }
        val adapter = adapterRegistry.adapter(request.source.key.adapterId)
            ?: return LibraryOperationResult.Rejected(
                "No operation adapter is registered for this source",
            )
        val availability = adapter.bookAvailability(request.source, request.operation)
        if (!availability.isAvailable) {
            return LibraryOperationResult.Rejected(requireNotNull(availability.reason))
        }
        return adapter.executeBookOperation(request)
    }
}
