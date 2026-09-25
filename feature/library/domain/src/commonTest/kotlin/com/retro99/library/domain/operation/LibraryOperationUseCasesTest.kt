package com.retro99.library.domain.operation

import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryBookOperation
import com.retro99.server.api.library.LibraryBookOperationAvailability
import com.retro99.server.api.library.LibraryBookOperationRequest
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.LibraryTransferStatus
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LibraryOperationUseCasesTest {
    @Test
    fun remoteBookRemovalRequiresConfirmationAndRunsThroughTheSourceAdapter() = runTest {
        // Given
        val adapter = RecordingAdapter(SOURCE_ADAPTER_ID)
        val useCase = ExecuteLibraryBookOperationUseCase(Registry(listOf(adapter)))
        val source = sourceRef()
        val unconfirmedRequest = remoteBookRemovalRequest(source, userConfirmed = false)

        // When
        val unconfirmedResult = useCase(unconfirmedRequest)

        // Then
        assertIs<LibraryOperationResult.Rejected>(unconfirmedResult)
        assertEquals(0, adapter.bookAvailabilityCount)
        assertEquals(0, adapter.bookExecutionCount)

        // When
        val request = remoteBookRemovalRequest(source, userConfirmed = true)
        val result = useCase(request)

        // Then
        assertEquals(LibraryOperationResult.Accepted(request.operationId), result)
        assertEquals(listOf(source to LibraryBookOperation.RemoveRemoteBook), adapter.bookChecks)
        assertEquals(listOf(request), adapter.bookRequests)
    }

    @Test
    fun unavailableRemoteBookRemovalDoesNotExecute() = runTest {
        // Given
        val adapter = RecordingAdapter(SOURCE_ADAPTER_ID, isAvailable = false)
        val useCase = ExecuteLibraryBookOperationUseCase(Registry(listOf(adapter)))
        val request = remoteBookRemovalRequest(sourceRef(), userConfirmed = true)

        // When
        val result = useCase(request)

        // Then
        assertIs<LibraryOperationResult.Rejected>(result)
        assertEquals(1, adapter.bookAvailabilityCount)
        assertEquals(0, adapter.bookExecutionCount)
    }

    @Test
    fun uploadExecutesThroughTheDestinationAdapterAfterCapabilityAndConfirmationChecks() = runTest {
        // Given
        val sourceAdapter = RecordingAdapter(SOURCE_ADAPTER_ID)
        val destinationAdapter = RecordingAdapter(DESTINATION_ADAPTER_ID)
        val useCase = ExecuteLibraryOperationUseCase(
            Registry(listOf(sourceAdapter, destinationAdapter)),
        )
        val request = uploadRequest(userConfirmed = true)

        // When
        val result = useCase(request)

        // Then
        assertEquals(LibraryOperationResult.Accepted(request.operationId), result)
        assertEquals(0, sourceAdapter.executionCount)
        assertEquals(1, destinationAdapter.executionCount)
    }

    @Test
    fun uploadWithoutUserConfirmationDoesNotQueryOrExecuteAnAdapter() = runTest {
        // Given
        val adapter = RecordingAdapter(DESTINATION_ADAPTER_ID)
        val useCase = ExecuteLibraryOperationUseCase(Registry(listOf(adapter)))

        // When
        val result = useCase(uploadRequest(userConfirmed = false))

        // Then
        assertIs<LibraryOperationResult.Rejected>(result)
        assertEquals(0, adapter.availabilityCount)
        assertEquals(0, adapter.executionCount)
    }

    @Test
    fun unavailableUploadCapabilityPreventsExecution() = runTest {
        // Given
        val adapter = RecordingAdapter(DESTINATION_ADAPTER_ID, isAvailable = false)
        val useCase = ExecuteLibraryOperationUseCase(Registry(listOf(adapter)))

        // When
        val result = useCase(uploadRequest(userConfirmed = true))

        // Then
        assertIs<LibraryOperationResult.Rejected>(result)
        assertEquals(1, adapter.availabilityCount)
        assertEquals(0, adapter.executionCount)
    }

    @Test
    fun adaptersWithoutNativeTransfersEmitAnEmptyObservation() = runTest {
        // Given
        val source = sourceRef()
        val adapters = listOf(
            RecordingAdapter(SOURCE_ADAPTER_ID),
            RecordingAdapter(DESTINATION_ADAPTER_ID),
        )
        val useCase = ObserveLibraryTransfersUseCase(Registry(adapters))

        // When
        val transfers = useCase(listOf(source)).first()

        // Then
        assertEquals(emptyList<LibraryTransferProgress>(), transfers)
    }

    @Test
    fun transferActionsUseTheOwningAdapterAndExactNativeTransferId() = runTest {
        val unrelatedAdapter = RecordingAdapter(SOURCE_ADAPTER_ID)
        val transferAdapter = RecordingAdapter(DESTINATION_ADAPTER_ID)
        val registry = Registry(listOf(unrelatedAdapter, transferAdapter))
        val transfer = LibraryTransferProgress(
            adapterId = DESTINATION_ADAPTER_ID,
            source = sourceRef(),
            transferId = LibraryTransferId("native-transfer-42"),
            operation = LibraryOperation.Upload,
            status = LibraryTransferStatus.Transferring,
            bytesTransferred = 10,
            totalBytes = 100,
            attemptCount = 1,
            canCancel = true,
            canRetry = false,
        )

        CancelLibraryTransferUseCase(registry)(transfer)
        RetryLibraryTransferUseCase(registry)(
            transfer.copy(
                status = LibraryTransferStatus.Failed,
                canCancel = false,
                canRetry = true,
            ),
        )

        assertEquals(listOf(LibraryTransferId("native-transfer-42")), transferAdapter.cancelledIds)
        assertEquals(listOf(LibraryTransferId("native-transfer-42")), transferAdapter.retriedIds)
        assertEquals(emptyList<LibraryTransferId>(), unrelatedAdapter.cancelledIds)
        assertEquals(emptyList<LibraryTransferId>(), unrelatedAdapter.retriedIds)
    }

    private fun uploadRequest(userConfirmed: Boolean) = LibraryOperationRequest(
        operationId = "upload-1",
        operation = LibraryOperation.Upload,
        assetId = MediaAssetId("epub-resource"),
        target = LibraryOperationTarget.UploadDestination(
            source = sourceRef(),
            resource = SourceResourceRef(sourceRef().key, "epub-resource"),
            assetId = MediaAssetId("epub-resource"),
            sourceReplicaId = StorageReplicaId("replica-1"),
            sourceStorageRef = DeviceStorageRef("/books/book.epub"),
            destinationAdapterId = DESTINATION_ADAPTER_ID,
            destinationAccount = SourceAccountIdentity.Portable("cloud", "account"),
            destinationConnectionId = SourceConnectionId("cloud-connection"),
        ),
        userConfirmed = userConfirmed,
    )

    private fun remoteBookRemovalRequest(
        source: SourceBookRef,
        userConfirmed: Boolean,
    ) = LibraryBookOperationRequest(
        operationId = "remove-remote-book-1",
        operation = LibraryBookOperation.RemoveRemoteBook,
        source = source,
        expectedSourceRevision = "revision-7",
        userConfirmed = userConfirmed,
    )

    private fun sourceRef() = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId("profile"),
            adapterId = SOURCE_ADAPTER_ID,
            accountIdentity = SourceAccountIdentity.Unresolved(
                SourceConnectionId("local-connection"),
            ),
            nativeBookId = NativeBookId("book"),
        ),
        connectionId = SourceConnectionId("local-connection"),
    )

    private class RecordingAdapter(
        override val adapterId: LibraryAdapterId,
        private val isAvailable: Boolean = true,
    ) : LibraryOperationAdapter {
        var availabilityCount = 0
        var executionCount = 0
        var bookAvailabilityCount = 0
        var bookExecutionCount = 0
        val bookChecks = mutableListOf<Pair<SourceBookRef, LibraryBookOperation>>()
        val bookRequests = mutableListOf<LibraryBookOperationRequest>()
        val cancelledIds = mutableListOf<LibraryTransferId>()
        val retriedIds = mutableListOf<LibraryTransferId>()

        override suspend fun availability(
            target: LibraryOperationTarget,
        ): List<OperationAvailability> {
            availabilityCount += 1
            return listOf(
                if (isAvailable) {
                    OperationAvailability(targetOperation(target), isAvailable = true)
                } else {
                    OperationAvailability(
                        targetOperation(target),
                        isAvailable = false,
                        reason = "Upload is disabled",
                    )
                },
            )
        }

        override suspend fun execute(
            request: LibraryOperationRequest,
        ): LibraryOperationResult {
            executionCount += 1
            return LibraryOperationResult.Accepted(request.operationId)
        }

        override suspend fun bookAvailability(
            source: SourceBookRef,
            operation: LibraryBookOperation,
        ): LibraryBookOperationAvailability {
            bookAvailabilityCount += 1
            bookChecks += source to operation
            return LibraryBookOperationAvailability(
                operation = operation,
                isAvailable = isAvailable,
                reason = if (isAvailable) null else "Book removal is disabled",
            )
        }

        override suspend fun executeBookOperation(
            request: LibraryBookOperationRequest,
        ): LibraryOperationResult {
            bookExecutionCount += 1
            bookRequests += request
            return LibraryOperationResult.Accepted(request.operationId)
        }

        override suspend fun cancelTransfer(transferId: LibraryTransferId) {
            cancelledIds += transferId
        }

        override suspend fun retryTransfer(transferId: LibraryTransferId) {
            retriedIds += transferId
        }

        private fun targetOperation(target: LibraryOperationTarget) = when (target) {
            is LibraryOperationTarget.UploadDestination -> LibraryOperation.Upload
            else -> LibraryOperation.RemoveDeviceReplica
        }
    }

    private class Registry(
        private val adapters: List<LibraryOperationAdapter>,
    ) : LibraryOperationAdapterRegistry {
        override fun adapter(adapterId: LibraryAdapterId): LibraryOperationAdapter? =
            adapters.firstOrNull { adapter -> adapter.adapterId == adapterId }

        override fun adapters(): List<LibraryOperationAdapter> = adapters
    }

    private companion object {
        val SOURCE_ADAPTER_ID = LibraryAdapterId("local")
        val DESTINATION_ADAPTER_ID = LibraryAdapterId("parrot-cloud")
    }
}
