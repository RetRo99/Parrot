package com.retro99.library.domain.operation

import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.LibraryTransferStatus
import com.retro99.server.api.library.SourceBookRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ObserveLibraryTransfersUseCase(
    @Provided private val adapterRegistry: LibraryOperationAdapterRegistry,
) {
    operator fun invoke(sources: List<SourceBookRef>): Flow<List<LibraryTransferProgress>> {
        val observations = sources.flatMap { source ->
            adapterRegistry.adapters().map { adapter -> adapter.observeTransfers(source) }
        }
        if (observations.isEmpty()) return flowOf(emptyList())
        return combine(observations) { emissions ->
            emissions.asSequence()
                .flatten()
                .distinctBy { transfer -> transfer.adapterId to transfer.transferId }
                .sortedBy { transfer -> transfer.transferId.value }
                .toList()
        }
    }
}

@Factory
class CancelLibraryTransferUseCase(
    @Provided private val adapterRegistry: LibraryOperationAdapterRegistry,
) {
    suspend operator fun invoke(transfer: LibraryTransferProgress) {
        check(transfer.canCancel && transfer.status in ACTIVE_TRANSFER_STATUSES) {
            "This transfer can no longer be cancelled"
        }
        requireNotNull(adapterRegistry.adapter(transfer.adapterId)) {
            "The transfer adapter is no longer registered"
        }.cancelTransfer(transfer.transferId)
    }
}

@Factory
class RetryLibraryTransferUseCase(
    @Provided private val adapterRegistry: LibraryOperationAdapterRegistry,
) {
    suspend operator fun invoke(transfer: LibraryTransferProgress) {
        check(transfer.canRetry && transfer.status == LibraryTransferStatus.Failed) {
            "This transfer cannot be retried"
        }
        requireNotNull(adapterRegistry.adapter(transfer.adapterId)) {
            "The transfer adapter is no longer registered"
        }.retryTransfer(transfer.transferId)
    }
}

private val ACTIVE_TRANSFER_STATUSES = setOf(
    LibraryTransferStatus.Queued,
    LibraryTransferStatus.Transferring,
    LibraryTransferStatus.Verifying,
    LibraryTransferStatus.Finalizing,
)
