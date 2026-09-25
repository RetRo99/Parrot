package com.retro99.server.api.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryOperationAndProgressTest {
    @Test
    fun exactOperationTargetCannotUseResourceFromAnotherSource() {
        val source = sourceRef("source")
        val resource = SourceResourceRef(sourceKey("other"), "resource")

        assertFailsWith<IllegalArgumentException> {
            LibraryOperationRequest(
                operationId = "operation",
                operation = LibraryOperation.Download,
                assetId = MediaAssetId("asset"),
                target = LibraryOperationTarget.RemoteReplica(
                    source = source,
                    resource = resource,
                    replicaId = StorageReplicaId("replica"),
                    remoteRef = RemoteResourceRef("remote"),
                ),
            )
        }
    }

    @Test
    fun unavailableOperationRequiresReason() {
        assertFailsWith<IllegalArgumentException> {
            OperationAvailability(LibraryOperation.Upload, isAvailable = false)
        }
    }

    @Test
    fun adapterOwnedProgressRetainsNativePayload() {
        val value: LibraryProgressValue = LibraryProgressValue.AdapterOwned(
            format = "fake/native-v2",
            payload = "chapter=abc;offset=17",
        )

        assertEquals("chapter=abc;offset=17", (value as LibraryProgressValue.AdapterOwned).payload)
    }

    @Test
    fun progressOwnerMustUseItsSourceAdapter() {
        assertFailsWith<IllegalArgumentException> {
            ProgressOwnerRef(
                adapterId = LibraryAdapterId("wrong"),
                source = sourceRef("book"),
                nativeProgressId = "progress",
            )
        }
    }

    @Test
    fun disconnectedPortableSourceCannotBecomeExecutableTarget() {
        val disconnected = sourceRef("book").copy(connectionId = null)
        val resource = SourceResourceRef(disconnected.key, "resource")

        assertFailsWith<IllegalArgumentException> {
            LibraryOperationRequest(
                operationId = "operation",
                operation = LibraryOperation.Download,
                assetId = MediaAssetId("asset"),
                target = LibraryOperationTarget.RemoteReplica(
                    source = disconnected,
                    resource = resource,
                    replicaId = StorageReplicaId("replica"),
                    remoteRef = RemoteResourceRef("remote"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgressOwnerRef(
                adapterId = disconnected.key.adapterId,
                source = disconnected,
                nativeProgressId = "progress",
            )
        }
    }

    @Test
    fun uploadCandidateRetainsTheExactAssetAndDeviceResource() {
        val source = uploadSource("native-book", "chapter-a")
        val adapterId = LibraryAdapterId("unlisted-upload-adapter")
        val candidate = uploadCandidate(source, adapterId)

        assertEquals(source, candidate.source)
        assertEquals(source.sourceReplica.resource, candidate.target.resource)
        assertEquals(adapterId, candidate.proposedBy)
        assertEquals("epub", candidate.acceptedFormat)
    }

    @Test
    fun uploadCandidateCannotChangeTheSourceResourceOrProposingAdapter() {
        val source = uploadSource("native-book", "chapter-a")
        val destination = LibraryOperationTarget.UploadDestination(
            source = source.sourceReplica.source,
            resource = SourceResourceRef(source.sourceReplica.source.key, "chapter-b"),
            assetId = source.assetId,
            sourceReplicaId = source.sourceReplica.replicaId,
            sourceStorageRef = source.sourceReplica.storageRef,
            destinationAdapterId = LibraryAdapterId("upload-adapter"),
            destinationAccount = SourceAccountIdentity.Portable("backend", "destination"),
            destinationConnectionId = SourceConnectionId("destination-connection"),
        )

        assertFailsWith<IllegalArgumentException> {
            LibraryUploadDestinationCandidate(
                proposedBy = LibraryAdapterId("upload-adapter"),
                source = source,
                target = destination,
                acceptedFormat = "epub",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LibraryUploadDestinationCandidate(
                proposedBy = LibraryAdapterId("another-adapter"),
                source = source,
                target = destination.copy(resource = source.sourceReplica.resource),
                acceptedFormat = "epub",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LibraryUploadDestinationCandidate(
                proposedBy = LibraryAdapterId("upload-adapter"),
                source = source,
                target = destination.copy(
                    resource = source.sourceReplica.resource,
                    assetId = MediaAssetId("another-asset"),
                ),
                acceptedFormat = "epub",
            )
        }
    }

    @Test
    fun uploadRequestCannotChangeTheResolvedAsset() {
        val source = uploadSource("native-book", "chapter-a")
        val candidate = uploadCandidate(source, LibraryAdapterId("upload-adapter"))

        assertFailsWith<IllegalArgumentException> {
            LibraryOperationRequest(
                operationId = "upload",
                operation = LibraryOperation.Upload,
                assetId = MediaAssetId("another-asset"),
                target = candidate.target,
            )
        }
    }

    private fun sourceKey(nativeId: String) = SourceBookKey(
        profileId = LibraryProfileId("profile"),
        adapterId = LibraryAdapterId("adapter"),
        accountIdentity = SourceAccountIdentity.Portable("backend", "account"),
        nativeBookId = NativeBookId(nativeId),
    )

    private fun sourceRef(nativeId: String) = SourceBookRef(
        key = sourceKey(nativeId),
        connectionId = SourceConnectionId("connection"),
    )

    private fun uploadSource(
        nativeBookId: String,
        resourceId: String,
    ): LibraryUploadSource {
        val source = sourceRef(nativeBookId)
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
            destinationAccount = SourceAccountIdentity.Portable("backend", "destination"),
            destinationConnectionId = SourceConnectionId("destination-connection"),
        ),
        acceptedFormat = source.format,
    )
}
