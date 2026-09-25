package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookType
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.reader.domain.model.DownloadState
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.LibraryTransferStatus
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LibraryGroupDetailOperationTargetTest {
    @Test
    fun downloadTargetRetainsTheExactRemoteResourceAndSource() {
        // Given
        val member = member(
            resource = remoteResource(
                nativeId = "source-resource-31",
                remoteId = "cloud-file-31",
            ),
        )

        // When
        val target = member.downloadTargetFor(member.snapshot.resources.single())

        // Then
        assertEquals(member.snapshot.source, target?.target?.source)
        assertEquals(member.snapshot.resources.single().reference, target?.target?.resource)
        assertEquals(RemoteResourceRef("cloud-file-31"), target?.target?.remoteRef)
        assertEquals("cloud-file-31", target?.target?.replicaId?.value)
        assertEquals(
            "7:profile12:parrot-cloud8:portable12:parrot-cloud9:account-113:cloud-book-12" +
                "18:source-resource-310:",
            target?.assetId?.value,
        )
    }

    @Test
    fun downloadTargetRequiresPresentRemoteResourceAndConnectedSource() {
        // Given
        val remoteResource = remoteResource("cloud-file-31", "cloud-file-31")
        val staleMember = member(
            resource = remoteResource,
            presence = SourcePresence.Unknown,
        )
        val localResource = remoteResource.copy(
            availability = SourceResourceAvailability.DevicePresent,
            remoteResourceReference = null,
        )
        val missingRemoteReference = member(
            resource = remoteResource.copy(remoteResourceReference = null),
        )
        val disconnectedMember = member(resource = remoteResource, connectionId = null)

        // When
        val staleTarget = staleMember.downloadTargetFor(remoteResource)
        val localTarget = member(localResource).downloadTargetFor(localResource)
        val missingRemoteTarget = missingRemoteReference.downloadTargetFor(
            missingRemoteReference.snapshot.resources.single(),
        )
        val disconnectedTarget = disconnectedMember.downloadTargetFor(remoteResource)

        // Then
        assertNull(staleTarget)
        assertNull(localTarget)
        assertNull(missingRemoteTarget)
        assertNull(disconnectedTarget)
    }

    @Test
    fun defaultMediaActionUsesPreferredReaderCacheResourceAndItsProgressOwner() {
        // Given
        val fallbackMember = member(deviceResource())
        val preferredSource = SourceBookRef(
            key = SourceBookKey(
                profileId = profileId,
                adapterId = LibraryAdapterId("storyteller"),
                accountIdentity = SourceAccountIdentity.Portable("storyteller", "account-1"),
                nativeBookId = NativeBookId("storyteller-book-9"),
            ),
            connectionId = SourceConnectionId("storyteller-connection"),
        )
        val preferredResource = SourceMediaResource(
            reference = SourceResourceRef(preferredSource.key, "storyteller-file-9"),
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("storyteller-file-9"),
        )
        val preferredMember = memberFor(
            preferredSource.key,
            preferredResource,
            connectionId = preferredSource.connectionId,
        )
        val group = LibraryBookGroup(
            profileId = profileId,
            groupId = LibraryGroupId("group-1"),
            displayMetadata = SourceBookMetadata("Book"),
            members = listOf(fallbackMember, preferredMember),
            preferredMediaSourceKeys = mapOf("ebook" to preferredSource.key),
        )
        val readerCacheAdapter = TestReaderCacheAdapter(preferredSource.key.adapterId)
        val readerCacheDownloadTarget = requireNotNull(
            preferredMember.downloadTargetFor(preferredResource),
        ).withReaderCacheSupport(readerCacheAdapter)

        // When
        val selectedTarget = requireNotNull(
            group.defaultMediaTargets(listOf(readerCacheDownloadTarget))["ebook"],
        )
        val selectedDownloadTarget = requireNotNull(selectedTarget.downloadTarget)
        val readerTarget = requireNotNull(selectedDownloadTarget.readerTarget("/cache/book.epub"))

        // Then
        assertEquals(preferredSource.key, selectedTarget.member.sourceKey)
        assertEquals(preferredResource.reference, selectedTarget.resource.reference)
        assertEquals(preferredSource, selectedDownloadTarget.target.source)
        assertEquals(preferredResource.reference, selectedDownloadTarget.target.resource)
        assertEquals(BookType.EBOOK, selectedDownloadTarget.bookType)
        assertEquals(preferredResource.reference, readerTarget.resource)
        assertEquals(
            ProgressOwnerRef(
                adapterId = LibraryAdapterId("storyteller"),
                source = preferredSource,
                nativeProgressId = "storyteller-book-9",
            ),
            readerTarget.progressOwner,
        )
    }

    @Test
    fun unknownAdapterCanDeclareReaderCacheForDetailsDownloadAndOpen() {
        // Given
        val adapterId = LibraryAdapterId("third-party-reader")
        val source = sourceKey.copy(
            adapterId = adapterId,
            nativeBookId = NativeBookId("third-party-book"),
        )
        val resource = SourceMediaResource(
            reference = SourceResourceRef(source, "third-party-resource"),
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("third-party-file"),
        )
        val member = memberFor(source, resource)
        val group = LibraryBookGroup(
            profileId = profileId,
            groupId = LibraryGroupId("third-party-group"),
            displayMetadata = SourceBookMetadata("Third party book"),
            members = listOf(member),
        )
        val adapter = TestReaderCacheAdapter(adapterId)
        val registry = TestOperationAdapterRegistry(listOf(adapter))
        val rawTarget = requireNotNull(member.downloadTargetFor(resource))

        // When
        assertFalse(rawTarget.isReaderCacheDownload())
        val registeredAdapter = requireNotNull(registry.adapter(adapterId))
        val target = rawTarget.withReaderCacheSupport(registeredAdapter)
        val defaultAction = requireNotNull(group.defaultMediaTargets(listOf(target))["ebook"])
        val request = target.toDownloadRequest("third-party-download-1")
        val readerTarget = target.readerTarget("/reader-cache/third-party-book.epub")

        // Then
        assertTrue(target.isReaderCacheDownload())
        assertEquals(target, defaultAction.downloadTarget)
        assertEquals(LibraryOperation.Download, request.operation)
        assertEquals(target.target, request.target)
        assertEquals(target.downloadCacheId, request.downloadCacheId)
        assertEquals("Book", request.displayTitle)
        assertEquals(adapterId, readerTarget.progressOwner.adapterId)
        assertEquals(target.target.resource, readerTarget.resource)
        assertEquals("/reader-cache/third-party-book.epub", readerTarget.storage.value)
    }

    @Test
    fun readerCacheFallbackSeparatesSourcesWithTheSameNativeBookId() {
        // Given
        val adapterId = LibraryAdapterId("third-party-reader")
        val firstConnection = SourceConnectionId("first-connection")
        val secondConnection = SourceConnectionId("second-connection")
        val firstSource = sourceKey.copy(
            adapterId = adapterId,
            accountIdentity = SourceAccountIdentity.Unresolved(firstConnection),
            nativeBookId = NativeBookId("shared-native-book"),
        )
        val secondSource = sourceKey.copy(
            adapterId = adapterId,
            accountIdentity = SourceAccountIdentity.Unresolved(secondConnection),
            nativeBookId = NativeBookId("shared-native-book"),
        )
        val firstResource = SourceMediaResource(
            reference = SourceResourceRef(firstSource, "shared-resource"),
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("first-file"),
        )
        val secondResource = firstResource.copy(
            reference = SourceResourceRef(secondSource, "shared-resource"),
            remoteResourceReference = RemoteResourceRef("second-file"),
        )
        val adapter = TestReaderCacheAdapter(adapterId)
        val firstTarget = requireNotNull(
            memberFor(firstSource, firstResource, connectionId = firstConnection)
                .downloadTargetFor(firstResource),
        ).withReaderCacheSupport(adapter)
        val secondTarget = requireNotNull(
            memberFor(secondSource, secondResource, connectionId = secondConnection)
                .downloadTargetFor(secondResource),
        ).withReaderCacheSupport(adapter)

        // Then
        assertTrue(firstTarget.isReaderCacheDownload())
        assertTrue(secondTarget.isReaderCacheDownload())
        assertNotEquals(firstTarget.downloadCacheId, secondTarget.downloadCacheId)
        assertNotEquals(
            firstTarget.toDownloadRequest("first-download").downloadCacheId,
            secondTarget.toDownloadRequest("second-download").downloadCacheId,
        )
    }

    @Test
    fun cachedReaderTargetSurvivesPresentToUnknownWithoutEnablingDownloadAvailability() {
        // Given
        val adapterId = LibraryAdapterId("storyteller")
        val source = sourceKey.copy(
            adapterId = adapterId,
            nativeBookId = NativeBookId("storyteller-book"),
        )
        val resource = SourceMediaResource(
            reference = SourceResourceRef(source, "storyteller-ebook"),
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("/api/v2/books/storyteller-book/files"),
        )
        val presentMember = memberFor(source, resource)
        val adapter = TestReaderCacheAdapter(adapterId)
        val registry = TestOperationAdapterRegistry(listOf(adapter))
        val presentGroup = groupWithMembers(
            groupId = "present-reader-cache",
            members = listOf(presentMember),
        )
        val presentCacheTarget = presentGroup.readerCacheTargets(registry).single()
        val presentDownloadTarget = requireNotNull(
            presentMember.downloadTargetFor(resource),
        ).withReaderCacheSupport(adapter)

        // When
        val unknownMember = presentMember.copy(
            snapshot = presentMember.snapshot.copy(
                status = presentMember.snapshot.status.copy(
                    presence = SourcePresence.Unknown,
                    isAuthoritative = false,
                ),
            ),
        )
        val unknownGroup = presentGroup.copy(members = listOf(unknownMember))
        val unknownCacheTarget = unknownGroup.readerCacheTargets(registry).single()

        // Then
        assertEquals(presentDownloadTarget.downloadCacheId, presentCacheTarget.cacheBookId)
        assertEquals(presentCacheTarget.cacheBookId, unknownCacheTarget.cacheBookId)
        assertTrue(unknownCacheTarget.isOpenableFrom(unknownMember, DownloadState.Cached))
        assertEquals(
            unknownCacheTarget,
            unknownGroup.defaultMediaTargets(
                availableDownloadTargets = emptyList(),
                cachedMediaTargets = listOf(unknownCacheTarget),
                downloadStates = mapOf(
                    unknownCacheTarget.resourceReference to DownloadState.Cached,
                ),
            )["ebook"]?.cachedMediaTarget,
        )
        assertTrue(unknownGroup.downloadTargets(registry).isEmpty())
        assertEquals(0, adapter.availabilityCalls)
    }

    @Test
    fun unknownReaderCacheDoesNotExposeOpenUntilTheCacheIsComplete() {
        // Given
        val adapterId = LibraryAdapterId("audiobookshelf")
        val source = sourceKey.copy(
            adapterId = adapterId,
            nativeBookId = NativeBookId("audiobookshelf-item"),
        )
        val resource = SourceMediaResource(
            reference = SourceResourceRef(source, "audiobookshelf-ebook"),
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("/api/items/item/file/file"),
        )
        val unknownMember = memberFor(
            source,
            resource,
            presence = SourcePresence.Unknown,
        )
        val target = requireNotNull(
            unknownMember.cachedMediaTargetFor(
                resource,
                TestReaderCacheAdapter(adapterId),
            ),
        )

        // Then
        assertFalse(target.isOpenableFrom(unknownMember, DownloadState.Idle))
        assertFalse(target.isOpenableFrom(unknownMember, DownloadState.Downloading(0.5f)))
        assertTrue(
            groupWithMembers(
                groupId = "incomplete-reader-cache",
                members = listOf(unknownMember),
            ).defaultMediaTargets(
                availableDownloadTargets = emptyList(),
                cachedMediaTargets = listOf(target),
                downloadStates = mapOf(target.resourceReference to DownloadState.Idle),
            ).isEmpty(),
        )
    }

    @Test
    fun cachedReaderTargetRejectsRemovedOrChangedResources() {
        // Given
        val adapterId = LibraryAdapterId("storyteller")
        val source = sourceKey.copy(adapterId = adapterId)
        val resource = SourceMediaResource(
            reference = SourceResourceRef(source, "storyteller-ebook"),
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("storyteller-file"),
        )
        val adapter = TestReaderCacheAdapter(adapterId)
        val target = requireNotNull(
            memberFor(source, resource).cachedMediaTargetFor(resource, adapter),
        )
        val removedMember = memberFor(source, resource).let { presentMember ->
            presentMember.copy(
                snapshot = presentMember.snapshot.copy(
                    status = presentMember.snapshot.status.copy(
                        presence = SourcePresence.Removed,
                        isAuthoritative = true,
                    ),
                ),
            )
        }
        val changedResource = resource.copy(
            remoteResourceReference = RemoteResourceRef("replacement-file"),
        )
        val changedMember = memberFor(
            source,
            changedResource,
            presence = SourcePresence.Unknown,
        )

        // Then
        assertFalse(target.isOpenableFrom(removedMember, DownloadState.Cached))
        assertFalse(target.isOpenableFrom(changedMember, DownloadState.Cached))
        assertTrue(
            groupWithMembers(
                groupId = "removed-reader-cache",
                members = listOf(removedMember),
            )
                .readerCacheTargets(TestOperationAdapterRegistry(listOf(adapter)))
                .isEmpty(),
        )
    }

    @Test
    fun arbitraryAdapterKeepsEveryAudiobookResourceAsAnExactDownloadTarget() {
        // Given
        val adapterId = LibraryAdapterId("third-party-audiobook-library")
        val source = sourceKey.copy(
            adapterId = adapterId,
            nativeBookId = NativeBookId("third-party-audiobook"),
        )
        val resources = listOf("chapter-1", "chapter-2").map { nativeId ->
            SourceMediaResource(
                reference = SourceResourceRef(source, nativeId),
                mediaType = BookType.AUDIOBOOK.value,
                format = "audio/mpeg",
                availability = SourceResourceAvailability.AvailableRemotely,
                remoteResourceReference = RemoteResourceRef("remote-$nativeId"),
            )
        }
        val member = memberForResources(source, resources)
        val group = LibraryBookGroup(
            profileId = profileId,
            groupId = LibraryGroupId("third-party-audiobook-group"),
            displayMetadata = SourceBookMetadata("Third party audiobook"),
            members = listOf(member),
        )
        val registry = TestOperationAdapterRegistry(
            listOf(TestReaderCacheAdapter(adapterId)),
        )

        // When
        val targets = group.downloadTargets(registry)

        // Then
        assertEquals(
            resources.map { resource -> resource.reference },
            targets.map { target -> target.target.resource },
        )
        assertEquals(
            resources.map { resource -> resource.remoteResourceReference },
            targets.map { target -> target.target.remoteRef },
        )
    }

    @Test
    fun defaultMediaActionFallsBackWhenPreferredSourceHasNoUsableResource() {
        // Given
        val fallbackMember = member(deviceResource())
        val unavailablePreferredSource = SourceBookRef(
            key = sourceKey.copy(
                adapterId = LibraryAdapterId("storyteller"),
                nativeBookId = NativeBookId("unavailable-book"),
            ),
            connectionId = SourceConnectionId("storyteller-connection"),
        )
        val unavailableResource = SourceMediaResource(
            reference = SourceResourceRef(unavailablePreferredSource.key, "remote-file"),
            mediaType = "ebook",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("remote-file"),
        )
        val unavailablePreferredMember = memberFor(
            source = unavailablePreferredSource.key,
            resource = unavailableResource,
            presence = SourcePresence.Unknown,
        )
        val group = LibraryBookGroup(
            profileId = profileId,
            groupId = LibraryGroupId("group-1"),
            displayMetadata = SourceBookMetadata("Book"),
            members = listOf(fallbackMember, unavailablePreferredMember),
            preferredMediaSourceKeys = mapOf("ebook" to unavailablePreferredSource.key),
        )

        // When
        val selectedTarget = group.defaultMediaTargets(emptyList())["ebook"]

        // Then
        assertEquals(fallbackMember.sourceKey, selectedTarget?.member?.sourceKey)
        assertEquals(deviceResource().reference, selectedTarget?.resource?.reference)
        assertFalse(selectedTarget?.isPreferred == true)
    }

    @Test
    fun downloadVisibilityRequiresAnAvailableDownloadCapability() {
        // Given
        val uploadOnly = listOf(
            OperationAvailability(LibraryOperation.Upload, isAvailable = true),
        )
        val disabledDownload = listOf(
            OperationAvailability(
                LibraryOperation.Download,
                isAvailable = false,
                reason = "This remote resource cannot be downloaded",
            ),
        )
        val enabledDownload = listOf(
            OperationAvailability(LibraryOperation.Download, isAvailable = true),
        )

        // Then
        assertFalse(uploadOnly.hasAvailableDownload())
        assertFalse(disabledDownload.hasAvailableDownload())
        assertTrue(enabledDownload.hasAvailableDownload())
    }

    @Test
    fun deviceReplicaRemovalTargetRetainsExactReplicaWithoutLocalAdapterAssumptions() {
        // Given
        val adapterId = LibraryAdapterId("third-party-library")
        val connectionId = SourceConnectionId("third-party-connection")
        val source = sourceKey.copy(
            adapterId = adapterId,
            accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
        )
        val deviceResource = deviceResource().copy(
            reference = SourceResourceRef(source, "resource-31"),
        )
        val member = memberFor(source, deviceResource, connectionId = connectionId)
        val groupId = LibraryGroupId("group-1")

        // When
        val target = member.deviceReplicaRemovalTargetFor(groupId, deviceResource)

        // Then
        assertEquals(groupId, target?.groupId)
        assertEquals(adapterId, target?.sourceKey?.adapterId)
        assertEquals(member.snapshot.source, target?.replica?.source)
        assertEquals(deviceResource.reference, target?.replica?.resource)
        assertEquals("/books/book.epub", target?.replica?.storageRef?.value)
        assertEquals("/books/book.epub", target?.replica?.replicaId?.value)
    }

    @Test
    fun remoteReplicaDeletionTargetRetainsExactResourceAndMediaForAnyAdapter() {
        // Given
        val source = sourceKey.copy(adapterId = LibraryAdapterId("third-party-library"))
        val resource = remoteResource(
            nativeId = "book-resource-31",
            remoteId = "cloud-file-31",
        ).copy(reference = SourceResourceRef(source, "book-resource-31"))
        val member = memberFor(source, resource)
        val groupId = LibraryGroupId("group-remote-delete")

        // When
        val target = member.remoteReplicaDeletionTargetFor(groupId, resource)

        // Then
        assertEquals(groupId, target?.groupId)
        assertEquals(member.sourceKey, target?.sourceKey)
        assertEquals(resource.reference, target?.resource)
        assertEquals(resource.reference, target?.target?.resource)
        assertEquals(RemoteResourceRef("cloud-file-31"), target?.target?.remoteRef)
        assertEquals("cloud-file-31", target?.target?.replicaId?.value)
        assertEquals("ebook", target?.mediaType)
        assertEquals("ebook", target?.target?.mediaType)
    }

    @Test
    fun deviceReplicaRemovalTargetRequiresTheCurrentDeviceReplica() {
        // Given
        val deviceResource = deviceResource()
        val staleMember = member(resource = deviceResource, presence = SourcePresence.Unknown)
        val foreignResource = deviceResource.copy(
            reference = SourceResourceRef(
                book = sourceKey.copy(nativeBookId = NativeBookId("other-book")),
                nativeResourceId = "resource-31",
            ),
        )
        val remoteOnly = SourceMediaResource(
            reference = deviceResource.reference,
            mediaType = "ebook",
            format = "epub",
            availability = SourceResourceAvailability.AvailableRemotely,
            remoteResourceReference = RemoteResourceRef("file-31"),
        )
        val disconnectedMember = member(resource = deviceResource, connectionId = null)

        // When
        val staleTarget = staleMember.deviceReplicaRemovalTargetFor(
            LibraryGroupId("group-1"),
            deviceResource,
        )
        val foreignTarget = member(deviceResource).deviceReplicaRemovalTargetFor(
            LibraryGroupId("group-1"),
            foreignResource,
        )
        val remoteTarget = member(remoteOnly).deviceReplicaRemovalTargetFor(
            LibraryGroupId("group-1"),
            remoteOnly,
        )
        val disconnectedTarget = disconnectedMember.deviceReplicaRemovalTargetFor(
            LibraryGroupId("group-1"),
            deviceResource,
        )

        // Then
        assertNull(staleTarget)
        assertNull(foreignTarget)
        assertNull(remoteTarget)
        assertNull(disconnectedTarget)
    }

    @Test
    fun deviceReplicaRemovalVisibilityRequiresTheMatchingCapability() {
        // Given
        val uploadOnly = listOf(
            OperationAvailability(LibraryOperation.Upload, isAvailable = true),
        )
        val disabledRemoval = listOf(
            OperationAvailability(
                LibraryOperation.RemoveDeviceReplica,
                isAvailable = false,
                reason = "This replica is in use",
            ),
        )
        val enabledRemoval = listOf(
            OperationAvailability(
                LibraryOperation.RemoveDeviceReplica,
                isAvailable = true,
            ),
        )

        // Then
        assertFalse(uploadOnly.hasAvailableDeviceReplicaRemoval())
        assertFalse(disabledRemoval.hasAvailableDeviceReplicaRemoval())
        assertTrue(enabledRemoval.hasAvailableDeviceReplicaRemoval())
    }

    @Test
    fun remoteReplicaDeletionVisibilityRequiresTheMatchingCapability() {
        // Given
        val downloadOnly = listOf(
            OperationAvailability(LibraryOperation.Download, isAvailable = true),
        )
        val disabledDeletion = listOf(
            OperationAvailability(
                LibraryOperation.DeleteRemoteReplica,
                isAvailable = false,
                reason = "This remote file cannot be deleted",
            ),
        )
        val enabledDeletion = listOf(
            OperationAvailability(LibraryOperation.DeleteRemoteReplica, isAvailable = true),
        )

        // Then
        assertFalse(downloadOnly.hasAvailableRemoteReplicaDeletion())
        assertFalse(disabledDeletion.hasAvailableRemoteReplicaDeletion())
        assertTrue(enabledDeletion.hasAvailableRemoteReplicaDeletion())
    }

    @Test
    fun remoteBookRemovalTargetIsSourceLevelAndIndependentOfResourceCount() {
        // Given
        val sourceMember = memberForResources(
            source = sourceKey,
            resources = listOf(
                remoteResource("ebook-resource", "ebook-file"),
                remoteResource("audiobook-resource", "audiobook-file").copy(
                    reference = SourceResourceRef(sourceKey, "audiobook-resource"),
                    mediaType = "audiobook",
                ),
            ),
        )
        val removedSource = sourceKey.copy(nativeBookId = NativeBookId("removed-book"))
        val removedResource = remoteResource("removed-resource", "removed-file").copy(
            reference = SourceResourceRef(removedSource, "removed-resource"),
        )
        val removedMember = memberFor(
            removedSource,
            resource = removedResource,
            presence = SourcePresence.Removed,
        )
        val disconnectedSource = sourceKey.copy(nativeBookId = NativeBookId("disconnected-book"))
        val disconnectedResource = remoteResource(
            "disconnected-resource",
            "disconnected-file",
        ).copy(reference = SourceResourceRef(disconnectedSource, "disconnected-resource"))
        val disconnectedMember = memberFor(
            disconnectedSource,
            resource = disconnectedResource,
            connectionId = null,
        )
        val group = groupWithMembers(
            groupId = "book-removal-group",
            members = listOf(sourceMember, removedMember, disconnectedMember),
        )

        // When
        val targets = group.remoteBookRemovalTargets()

        // Then
        assertEquals(1, targets.size)
        assertEquals(sourceMember.sourceKey, targets.single().sourceKey)
        assertEquals(sourceMember.snapshot.source, targets.single().source)
        assertEquals(group.groupId, targets.single().groupId)
        assertEquals(sourceMember.snapshot.resources, targets.single().observedResources)
        val afterFileRemoval = group.copy(
            members = listOf(
                sourceMember.copy(
                    snapshot = sourceMember.snapshot.copy(resources = emptyList()),
                ),
            ),
        ).remoteBookRemovalTargets().single()
        assertNotEquals(targets.single(), afterFileRemoval)
    }

    @Test
    fun transferActionIdentityIncludesItsOwningAdapter() {
        // Given
        val transferId = LibraryTransferId("transfer-1")
        val firstAdapter = LibraryAdapterId("adapter-a")
        val secondAdapter = LibraryAdapterId("adapter-b")
        val firstTransfer = transfer(firstAdapter, transferId)
        val secondTransfer = transfer(secondAdapter, transferId)
        val state = LibraryGroupDetailViewState(
            activeTransferAction = LibraryTransferActionKey(firstAdapter, transferId),
        )

        // Then
        assertTrue(state.isTransferActionRunning(firstTransfer))
        assertFalse(state.isTransferActionRunning(secondTransfer))
    }

    private fun member(
        resource: SourceMediaResource,
        presence: SourcePresence = SourcePresence.Present,
        connectionId: SourceConnectionId? = sourceConnectionId,
    ): LibraryGroupMember = memberFor(sourceKey, resource, presence, connectionId)

    private fun groupWithMembers(
        groupId: String,
        members: List<LibraryGroupMember>,
    ): LibraryBookGroup = LibraryBookGroup(
        profileId = profileId,
        groupId = LibraryGroupId(groupId),
        displayMetadata = members.firstOrNull()?.snapshot?.metadata
            ?: SourceBookMetadata("Book"),
        members = members,
    )

    private fun memberFor(
        source: SourceBookKey,
        resource: SourceMediaResource,
        presence: SourcePresence = SourcePresence.Present,
        connectionId: SourceConnectionId? = SourceConnectionId(
            "connection-${source.adapterId.value}",
        ),
    ): LibraryGroupMember = memberForResources(
        source = source,
        resources = listOf(resource),
        presence = presence,
        connectionId = connectionId,
    )

    private fun memberForResources(
        source: SourceBookKey,
        resources: List<SourceMediaResource>,
        presence: SourcePresence = SourcePresence.Present,
        connectionId: SourceConnectionId? = SourceConnectionId(
            "connection-${source.adapterId.value}",
        ),
    ): LibraryGroupMember {
        val snapshot = SourceBookSnapshot(
            source = SourceBookRef(source, connectionId),
            metadata = SourceBookMetadata("Book"),
            resources = resources,
            status = SourceSnapshotStatus(
                observedAt = Instant.parse("2026-09-25T00:00:00Z"),
                presence = presence,
                isAuthoritative = presence == SourcePresence.Removed,
            ),
        )
        return LibraryGroupMember(
            snapshot = snapshot,
            membershipOrigin = LibraryMembershipOrigin.Automatic,
            membershipRevision = 1,
            decisionId = null,
        )
    }

    private fun remoteResource(nativeId: String, remoteId: String) = SourceMediaResource(
        reference = SourceResourceRef(sourceKey, nativeId),
        mediaType = "ebook",
        format = "epub",
        availability = SourceResourceAvailability.AvailableRemotely,
        remoteResourceReference = RemoteResourceRef(remoteId),
    )

    private fun deviceResource() = SourceMediaResource(
        reference = SourceResourceRef(sourceKey, "resource-31"),
        mediaType = "ebook",
        format = "epub",
        availability = SourceResourceAvailability.DevicePresent,
        localStorageReference = DeviceStorageRef("/books/book.epub"),
    )

    private fun transfer(
        adapterId: LibraryAdapterId,
        transferId: LibraryTransferId,
    ) = LibraryTransferProgress(
        adapterId = adapterId,
        source = SourceBookRef(sourceKey, sourceConnectionId),
        transferId = transferId,
        operation = LibraryOperation.Download,
        status = LibraryTransferStatus.Transferring,
        bytesTransferred = 1,
        totalBytes = 10,
        attemptCount = 1,
        canCancel = true,
        canRetry = false,
    )

    private class TestReaderCacheAdapter(
        override val adapterId: LibraryAdapterId,
    ) : LibraryOperationAdapter {
        var availabilityCalls = 0

        override fun supportsReaderCache(
            target: LibraryOperationTarget.RemoteReplica,
        ): Boolean = target.source.key.adapterId == adapterId

        override suspend fun availability(
            target: LibraryOperationTarget,
        ): List<OperationAvailability> {
            availabilityCalls += 1
            return listOf(OperationAvailability(LibraryOperation.Download, isAvailable = true))
        }

        override suspend fun execute(
            request: LibraryOperationRequest,
        ): LibraryOperationResult = LibraryOperationResult.Accepted(request.operationId)
    }

    private class TestOperationAdapterRegistry(
        private val registeredAdapters: List<LibraryOperationAdapter>,
    ) : LibraryOperationAdapterRegistry {
        override fun adapter(adapterId: LibraryAdapterId): LibraryOperationAdapter? =
            registeredAdapters.firstOrNull { adapter -> adapter.adapterId == adapterId }

        override fun adapters(): List<LibraryOperationAdapter> = registeredAdapters
    }

    private companion object {
        val profileId = LibraryProfileId("profile")
        val sourceConnectionId = SourceConnectionId("cloud-connection")
        val sourceKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "account-1"),
            nativeBookId = NativeBookId("cloud-book-12"),
        )
    }
}
