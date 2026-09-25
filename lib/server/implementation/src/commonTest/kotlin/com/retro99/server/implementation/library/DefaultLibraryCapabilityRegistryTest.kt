package com.retro99.server.implementation.library

import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryProgressAdapter
import com.retro99.server.api.library.LibraryProgressValue
import com.retro99.server.api.library.LibraryUploadDestinationCandidate
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultLibraryCapabilityRegistryTest {
    @Test
    fun arbitraryOperationAndProgressAdaptersRegisterIndependently() {
        val id = LibraryAdapterId("unknown-capability-adapter")
        val operation = FakeOperationAdapter(id)
        val progress = FakeProgressAdapter(id)

        assertEquals(
            operation,
            DefaultLibraryOperationAdapterRegistry(listOf(operation)).adapter(id),
        )
        assertEquals(progress, DefaultLibraryProgressAdapterRegistry(listOf(progress)).adapter(id))
    }

    @Test
    fun operationRegistryEnumeratesArbitraryAdaptersAndTheirUploadProposals() = runTest {
        val source = uploadSource("book-one", "epub-one")
        val adapterId = LibraryAdapterId("third-party-upload")
        val adapter = FakeOperationAdapter(
            adapterId = adapterId,
            uploadProposals = listOf(uploadCandidate(source, adapterId)),
        )
        val registry = DefaultLibraryOperationAdapterRegistry(listOf(adapter))

        assertEquals(listOf(adapter), registry.adapters())
        assertEquals(
            listOf(uploadCandidate(source, adapterId)),
            registry.adapters().single().proposeUploadDestinations(source),
        )
    }

    @Test
    fun duplicateOperationAndProgressAdaptersAreRejected() {
        val id = LibraryAdapterId("duplicate")

        assertFailsWith<IllegalArgumentException> {
            DefaultLibraryOperationAdapterRegistry(
                listOf(FakeOperationAdapter(id), FakeOperationAdapter(id)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DefaultLibraryProgressAdapterRegistry(
                listOf(FakeProgressAdapter(id), FakeProgressAdapter(id)),
            )
        }
    }
}

private class FakeOperationAdapter(
    override val adapterId: LibraryAdapterId,
    private val uploadProposals: List<LibraryUploadDestinationCandidate> = emptyList(),
) : LibraryOperationAdapter {
    override suspend fun proposeUploadDestinations(
        source: LibraryUploadSource,
    ): List<LibraryUploadDestinationCandidate> = uploadProposals.filter { proposal ->
        proposal.source == source
    }

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> = emptyList()

    override suspend fun execute(request: LibraryOperationRequest): LibraryOperationResult =
        LibraryOperationResult.Accepted(request.operationId)
}

private class FakeProgressAdapter(
    override val adapterId: LibraryAdapterId,
) : LibraryProgressAdapter {
    override suspend fun read(owner: ProgressOwnerRef): LibraryProgressValue? = null

    override suspend fun write(owner: ProgressOwnerRef, value: LibraryProgressValue) = Unit
}

private fun uploadSource(
    nativeBookId: String,
    resourceId: String,
): LibraryUploadSource {
    val source = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId("profile"),
            adapterId = LibraryAdapterId("source-adapter"),
            accountIdentity = SourceAccountIdentity.Portable("backend", "account"),
            nativeBookId = NativeBookId(nativeBookId),
        ),
        connectionId = SourceConnectionId("source-connection"),
    )
    val resource = SourceResourceRef(source.key, resourceId)
    return LibraryUploadSource(
        assetId = MediaAssetId("asset-$resourceId"),
        sourceReplica = LibraryOperationTarget.DeviceReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId("replica-$resourceId"),
            storageRef = DeviceStorageRef("/books/$resourceId.epub"),
        ),
        format = "epub",
    )
}

private fun uploadCandidate(
    source: LibraryUploadSource,
    adapterId: LibraryAdapterId,
) = LibraryUploadDestinationCandidate(
    proposedBy = adapterId,
    source = source,
    target = LibraryOperationTarget.UploadDestination(
        source = source.sourceReplica.source,
        resource = source.sourceReplica.resource,
        assetId = source.assetId,
        sourceReplicaId = source.sourceReplica.replicaId,
        sourceStorageRef = source.sourceReplica.storageRef,
        destinationAdapterId = adapterId,
        destinationAccount = SourceAccountIdentity.Portable("cloud", "account"),
        destinationConnectionId = SourceConnectionId("cloud-connection"),
    ),
    acceptedFormat = source.format,
)
