package com.retro99.library.domain.operation

import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ExecuteLibraryOperationUseCase(
    @Provided private val adapterRegistry: LibraryOperationAdapterRegistry,
) {
    suspend operator fun invoke(request: LibraryOperationRequest): LibraryOperationResult {
        if (request.operation == LibraryOperation.Upload && !request.userConfirmed) {
            return LibraryOperationResult.Rejected(
                "Confirm the upload rights before starting this upload",
            )
        }
        val adapterId = when (val target = request.target) {
            is LibraryOperationTarget.UploadDestination -> target.destinationAdapterId
            else -> target.source.key.adapterId
        }
        val adapter = adapterRegistry.adapter(adapterId)
            ?: return LibraryOperationResult.Rejected(
                "No operation adapter is registered for this source",
            )
        val availability = adapter.availability(request.target)
            .firstOrNull { result -> result.operation == request.operation }
            ?: return LibraryOperationResult.Rejected(
                "This source does not support the requested operation",
            )
        if (!availability.isAvailable) {
            return LibraryOperationResult.Rejected(requireNotNull(availability.reason))
        }
        return adapter.execute(request)
    }
}
