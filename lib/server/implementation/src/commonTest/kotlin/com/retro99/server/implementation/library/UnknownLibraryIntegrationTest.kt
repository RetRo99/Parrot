package com.retro99.server.implementation.library

import com.retro99.library.domain.grouping.LibraryGroupMemberSelection
import com.retro99.library.domain.grouping.LibraryIdentityEvidence
import com.retro99.library.domain.grouping.LibraryIdentityResolver
import com.retro99.library.domain.grouping.LibraryManualGroupingRepository
import com.retro99.library.domain.grouping.LibraryMembershipAssignment
import com.retro99.library.domain.grouping.MergeLibraryGroupMembersUseCase
import com.retro99.library.domain.grouping.SplitLibraryGroupMembersUseCase
import com.retro99.library.domain.operation.ExecuteLibraryOperationUseCase
import com.retro99.library.domain.operation.LibraryUploadTargetResolver
import com.retro99.library.domain.operation.LibraryUploadUnavailableReason
import com.retro99.library.domain.projection.LibraryGroupProjector
import com.retro99.library.domain.projection.readerTargetFor
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProgressAdapter
import com.retro99.server.api.library.LibraryProgressAdapterRegistry
import com.retro99.server.api.library.LibraryProgressValue
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.LibrarySourceRecord
import com.retro99.server.api.library.LibraryUploadDestinationCandidate
import com.retro99.server.api.library.LibraryUploadDestinationProposal
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.MediaAssetId
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
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.server.api.library.StorageReplicaId
import com.retro99.server.implementation.library.DefaultLibraryOperationAdapterRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class UnknownLibraryIntegrationTest {
    @Test
    fun unknownOperationAdapterCanChangeDownloadAvailability() = runTest {
        // Given
        val adapterId = LibraryAdapterId("third-party-reader")
        val connectionId = SourceConnectionId("third-party-connection")
        val key = SourceBookKey(
            profileId = LibraryProfileId("profile"),
            adapterId = adapterId,
            accountIdentity = SourceAccountIdentity.Portable("third-party-backend", "account"),
            nativeBookId = NativeBookId("book"),
        )
        val source = SourceBookRef(key, connectionId)
        val target = LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = SourceResourceRef(key, "remote-epub"),
            replicaId = StorageReplicaId("remote-replica"),
            remoteRef = RemoteResourceRef("remote-file"),
        )
        val adapter = ToggleableOperationAdapter(adapterId)
        val registry = DefaultLibraryOperationAdapterRegistry(listOf(adapter))
        val enabledDownload = OperationAvailability(LibraryOperation.Download, isAvailable = true)

        // When
        adapter.operationAvailability = listOf(enabledDownload)
        val enabledOperations = registry.adapter(adapterId)?.availability(target)
        adapter.operationAvailability = listOf(
            OperationAvailability(
                LibraryOperation.Download,
                isAvailable = false,
                reason = "The remote source is offline",
            ),
        )
        val disabledOperations = registry.adapter(adapterId)?.availability(target)

        // Then
        assertEquals(listOf(enabledDownload), enabledOperations)
        assertEquals("The remote source is offline", disabledOperations?.single()?.reason)
    }

    @Test
    fun unknownAdapterCapabilitiesResolveAndExecuteThroughGenericOperationBoundaries() = runTest {
        // Given
        val profileId = LibraryProfileId("profile")
        val adapterId = LibraryAdapterId("third-party-reader")
        val connectionId = SourceConnectionId("third-party-connection")
        val account = SourceAccountIdentity.Portable("third-party-backend", "account")
        val key = SourceBookKey(
            profileId = profileId,
            adapterId = adapterId,
            accountIdentity = account,
            nativeBookId = NativeBookId("book"),
        )
        val source = SourceBookRef(key, connectionId)
        val resource = SourceResourceRef(key, "ebook")
        val assetId = MediaAssetId("book-asset")
        val deviceReplica = LibraryOperationTarget.DeviceReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId("device-replica"),
            storageRef = DeviceStorageRef("/third-party/book.epub"),
        )
        val uploadSource = LibraryUploadSource(
            assetId = assetId,
            sourceReplica = deviceReplica,
            format = "ebook",
        )
        val uploadTarget = LibraryOperationTarget.UploadDestination(
            source = source,
            resource = resource,
            assetId = assetId,
            sourceReplicaId = deviceReplica.replicaId,
            sourceStorageRef = deviceReplica.storageRef,
            destinationAdapterId = adapterId,
            destinationAccount = account,
            destinationConnectionId = connectionId,
        )
        val adapter = ToggleableOperationAdapter(adapterId).apply {
            uploadCandidate = LibraryUploadDestinationCandidate(
                proposedBy = adapterId,
                source = uploadSource,
                target = uploadTarget,
                acceptedFormat = "ebook",
            )
            operationAvailability = listOf(
                OperationAvailability(LibraryOperation.Upload, isAvailable = true),
                OperationAvailability(LibraryOperation.Download, isAvailable = true),
            )
        }
        val registry = DefaultLibraryOperationAdapterRegistry(listOf(adapter))
        val registeredAdapter = requireNotNull(registry.adapter(adapterId))
        val connectedConnections = setOf(connectionId)
        val authenticatedAccounts = setOf(account)

        // When
        val availableCandidate = registeredAdapter
            .proposeUploadDestinations(uploadSource)
            .single()
        val availableUploadResolution = LibraryUploadTargetResolver.resolve(
            profileId = profileId,
            sources = listOf(uploadSource),
            proposals = listOf(
                LibraryUploadDestinationProposal(
                    candidate = availableCandidate,
                    availability = registeredAdapter.availability(availableCandidate.target),
                ),
            ),
            registeredAdapters = setOf(adapterId),
            connectedConnections = connectedConnections,
            authenticatedAccounts = authenticatedAccounts,
            sourceAvailability = mapOf(
                deviceReplica to SourceResourceAvailability.DevicePresent,
            ),
        )
        val downloadRequest = LibraryOperationRequest(
            operationId = "download-1",
            operation = LibraryOperation.Download,
            assetId = assetId,
            target = LibraryOperationTarget.RemoteReplica(
                source = source,
                resource = resource,
                replicaId = StorageReplicaId("remote-replica"),
                remoteRef = RemoteResourceRef("remote-file"),
                mediaType = "ebook",
            ),
        )
        val downloadResult = ExecuteLibraryOperationUseCase(registry)(downloadRequest)
        val uploadRequest = LibraryOperationRequest(
            operationId = "upload-1",
            operation = LibraryOperation.Upload,
            assetId = assetId,
            target = availableCandidate.target,
            userConfirmed = true,
        )
        val uploadResult = ExecuteLibraryOperationUseCase(registry)(uploadRequest)

        adapter.operationAvailability = listOf(
            OperationAvailability(
                LibraryOperation.Upload,
                isAvailable = false,
                reason = "Uploads are disabled by this integration",
            ),
            OperationAvailability(
                LibraryOperation.Download,
                isAvailable = false,
                reason = "Downloads are disabled by this integration",
            ),
        )
        val disabledCandidate = registeredAdapter
            .proposeUploadDestinations(uploadSource)
            .single()
        val disabledUploadResolution = LibraryUploadTargetResolver.resolve(
            profileId = profileId,
            sources = listOf(uploadSource),
            proposals = listOf(
                LibraryUploadDestinationProposal(
                    candidate = disabledCandidate,
                    availability = registeredAdapter.availability(disabledCandidate.target),
                ),
            ),
            registeredAdapters = setOf(adapterId),
            connectedConnections = connectedConnections,
            authenticatedAccounts = authenticatedAccounts,
            sourceAvailability = mapOf(
                deviceReplica to SourceResourceAvailability.DevicePresent,
            ),
        )
        val disabledDownloadResult = ExecuteLibraryOperationUseCase(registry)(downloadRequest)

        // Then
        assertEquals(1, availableUploadResolution.available.size)
        assertEquals(uploadTarget, availableUploadResolution.available.single().target)
        assertEquals(
            LibraryOperationResult.Accepted(downloadRequest.operationId),
            downloadResult,
        )
        assertEquals(
            LibraryOperationResult.Accepted(uploadRequest.operationId),
            uploadResult,
        )
        assertEquals(listOf(downloadRequest, uploadRequest), adapter.executedRequests)
        assertEquals(
            LibraryUploadUnavailableReason.UploadCapabilityDisabled,
            disabledUploadResolution.unavailable.single().reason,
        )
        assertEquals(
            "Uploads are disabled by this integration",
            disabledUploadResolution.unavailable.single().detail,
        )
        assertEquals(
            LibraryOperationResult.Rejected("Downloads are disabled by this integration"),
            disabledDownloadResult,
        )
        assertEquals(listOf(downloadRequest, uploadRequest), adapter.executedRequests)
    }

    @Test
    fun unknownIntegrationUsesVerifiedIdentityAndGenericProjectionContracts() = runTest {
        // Given
        val profileId = LibraryProfileId("profile")
        val adapter = UnknownLibrarySourceAdapter(profileId)
        val sourceRegistry = DefaultLibrarySourceAdapterRegistry(listOf(adapter))
        val known = knownSnapshot(profileId)
        val verifiedMatch = sourceRegistry.normalize(
            adapter.adapterId,
            UnknownLibraryRecord("verified-copy", "Verified copy", "same-hash"),
        )
        val noEvidence = sourceRegistry.normalize(
            adapter.adapterId,
            UnknownLibraryRecord("unverified-copy", "Unverified copy", null),
        )
        val snapshots = listOf(known, verifiedMatch, noEvidence)
        val memberships = listOf(
            LibraryMembershipAssignment(known.source.key, LibraryGroupId("group-known")),
            LibraryMembershipAssignment(
                verifiedMatch.source.key,
                LibraryGroupId("group-verified"),
            ),
            LibraryMembershipAssignment(
                noEvidence.source.key,
                LibraryGroupId("group-unverified"),
            ),
        )
        val evidence = snapshots.flatMapIndexed { snapshotIndex, snapshot ->
            snapshot.identityEvidence.mapIndexed { evidenceIndex, identityEvidence ->
                LibraryIdentityEvidence("snapshot-$snapshotIndex-$evidenceIndex", identityEvidence)
            }
        }

        // When
        val plan = LibraryIdentityResolver.resolve(memberships, emptySet(), evidence)

        // Then
        val automaticJoin = plan.automaticJoins.single()
        assertEquals(setOf(known.source.key, verifiedMatch.source.key), automaticJoin.sourceKeys)
        assertTrue(noEvidence.source.key !in automaticJoin.sourceKeys)

        val joinedGroupIdBySource = automaticJoin.sourceKeys.associateWith {
            automaticJoin.survivorGroupId
        }
        val projectedMemberships = memberships.map { membership ->
            val joinedGroupId = joinedGroupIdBySource[membership.source]
            if (joinedGroupId == null) {
                membership
            } else {
                membership.copy(
                    groupId = joinedGroupId,
                    origin = LibraryMembershipOrigin.Automatic,
                )
            }
        }
        val groups = LibraryGroupProjector.project(projectedMemberships, snapshots)
        val groupedSources = groups.single { group ->
            group.members.any { member -> member.sourceKey == verifiedMatch.source.key }
        }
        val unknownMember = groupedSources.members.single { member ->
            member.sourceKey == verifiedMatch.source.key
        }
        val resource = unknownMember.snapshot.resources.single { mediaResource ->
            mediaResource.mediaType == "audiobook"
        }
        val readerTarget = assertNotNull(unknownMember.readerTargetFor(resource))
        assertEquals(SourceResourceAvailability.DevicePresent, resource.availability)
        assertEquals("audiobook", readerTarget.mediaType)
        assertEquals("Verified copy", readerTarget.title)
        assertEquals(
            DeviceStorageRef("/third-party/verified-copy.m4b"),
            readerTarget.storage,
        )
        assertEquals("third-party-reader", readerTarget.progressOwner.adapterId.value)

        val expectedProgress = LibraryProgressValue.AdapterOwned(
            format = "third-party-reader-v1",
            payload = "chapter=4;offset=0.42",
        )
        val progressAdapter = RecordingProgressAdapter(adapter.adapterId, expectedProgress)
        val progressRegistry: LibraryProgressAdapterRegistry =
            DefaultLibraryProgressAdapterRegistry(listOf(progressAdapter))
        val resolvedProgress = progressRegistry.adapter(readerTarget.progressOwner.adapterId)
            ?.read(readerTarget.progressOwner)

        assertEquals(readerTarget.progressOwner, progressAdapter.lastOwnerRead)
        assertEquals(expectedProgress, resolvedProgress)
    }

    @Test
    fun unknownIntegrationWithoutEvidenceCanStillBeManuallyMergedAndSplit() = runTest {
        // Given
        val profileId = LibraryProfileId("profile")
        val adapter = UnknownLibrarySourceAdapter(profileId)
        val sourceRegistry = DefaultLibrarySourceAdapterRegistry(listOf(adapter))
        val unknown = sourceRegistry.normalize(
            adapter.adapterId,
            UnknownLibraryRecord("unverified-copy", "Unverified copy", null),
        )
        val otherSource = knownSnapshot(profileId)
        val repository = RecordingManualGroupingRepository()
        val mergeUseCase = MergeLibraryGroupMembersUseCase(repository)
        val selectedMembers = listOf(
            LibraryGroupMemberSelection(
                unknown.source.key,
                LibraryGroupId("group-unverified"),
            ),
            LibraryGroupMemberSelection(otherSource.source.key, LibraryGroupId("group-known")),
        )

        // When
        val mergedGroup = mergeUseCase(
            profileId,
            selectedMembers,
            preferredMetadataSourceKey = unknown.source.key,
        )
        val splitGroup = SplitLibraryGroupMembersUseCase(repository)(
            profileId,
            mergedGroup,
            listOf(unknown.source.key),
        )

        // Then
        assertEquals(LibraryGroupId("manual-merge"), mergedGroup)
        assertEquals(selectedMembers, repository.mergedMembers)
        assertEquals(unknown.source.key, repository.preferredMetadataSourceKey)
        assertEquals(LibraryGroupId("manual-split"), splitGroup)
        assertEquals(mergedGroup, repository.splitSourceGroupId)
        assertEquals(listOf(unknown.source.key), repository.splitSourceKeys)
    }
}

private data class UnknownLibraryRecord(
    val nativeBookId: String,
    val title: String,
    val verifiedHash: String?,
) : LibrarySourceRecord

private class UnknownLibrarySourceAdapter(
    private val profileId: LibraryProfileId,
) : LibrarySourceAdapter {
    override val adapterId = LibraryAdapterId("third-party-reader")

    override fun normalize(record: LibrarySourceRecord): SourceBookSnapshot {
        val unknownRecord = record as UnknownLibraryRecord
        val connectionId = SourceConnectionId("third-party-connection")
        val key = SourceBookKey(
            profileId = profileId,
            adapterId = adapterId,
            accountIdentity = SourceAccountIdentity.Portable(
                backendId = "third-party-backend",
                accountId = "third-party-account",
            ),
            nativeBookId = NativeBookId(unknownRecord.nativeBookId),
        )
        val resources = listOf(
            SourceMediaResource(
                reference = SourceResourceRef(key, "${unknownRecord.nativeBookId}-epub"),
                mediaType = "ebook",
                availability = SourceResourceAvailability.DevicePresent,
                localStorageReference = DeviceStorageRef(
                    "/third-party/${unknownRecord.nativeBookId}.epub",
                ),
            ),
            SourceMediaResource(
                reference = SourceResourceRef(key, "${unknownRecord.nativeBookId}-audio"),
                mediaType = "audiobook",
                availability = SourceResourceAvailability.DevicePresent,
                localStorageReference = DeviceStorageRef(
                    "/third-party/${unknownRecord.nativeBookId}.m4b",
                ),
            ),
        )
        val evidenceResource = SourceResourceRef(key, "${unknownRecord.nativeBookId}-epub")
        val identityEvidence = unknownRecord.verifiedHash?.let { hash ->
            listOf(
                SourceIdentityEvidence.FileFingerprint(
                    resource = evidenceResource,
                    algorithm = "sha-256-v1",
                    hash = hash,
                    scope = FingerprintScope.WholeFile,
                    verification = FingerprintVerification.Verified,
                ),
            )
        }.orEmpty()
        return SourceBookSnapshot(
            source = SourceBookRef(key, connectionId),
            metadata = SourceBookMetadata(title = unknownRecord.title),
            resources = resources,
            identityEvidence = identityEvidence,
            status = presentStatus(),
        )
    }
}

private class RecordingProgressAdapter(
    override val adapterId: LibraryAdapterId,
    private val progress: LibraryProgressValue,
) : LibraryProgressAdapter {
    var lastOwnerRead: ProgressOwnerRef? = null

    override suspend fun read(owner: ProgressOwnerRef): LibraryProgressValue {
        lastOwnerRead = owner
        return progress
    }

    override suspend fun write(owner: ProgressOwnerRef, value: LibraryProgressValue) = Unit
}

private class ToggleableOperationAdapter(
    override val adapterId: LibraryAdapterId,
) : LibraryOperationAdapter {
    var operationAvailability: List<OperationAvailability> = emptyList()
    var uploadCandidate: LibraryUploadDestinationCandidate? = null
    val executedRequests = mutableListOf<LibraryOperationRequest>()

    override suspend fun proposeUploadDestinations(
        source: LibraryUploadSource,
    ): List<LibraryUploadDestinationCandidate> =
        listOfNotNull(uploadCandidate?.takeIf { candidate -> candidate.source == source })

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> = operationAvailability

    override suspend fun execute(request: LibraryOperationRequest): LibraryOperationResult {
        executedRequests += request
        return LibraryOperationResult.Accepted(request.operationId)
    }
}

private class RecordingManualGroupingRepository : LibraryManualGroupingRepository {
    var mergedMembers: List<LibraryGroupMemberSelection>? = null
    var preferredMetadataSourceKey: SourceBookKey? = null
    var splitSourceGroupId: LibraryGroupId? = null
    var splitSourceKeys: List<SourceBookKey>? = null

    override suspend fun mergeMembers(
        profileId: LibraryProfileId,
        members: List<LibraryGroupMemberSelection>,
        preferredMetadataSourceKey: SourceBookKey?,
    ): LibraryGroupId {
        mergedMembers = members
        this.preferredMetadataSourceKey = preferredMetadataSourceKey
        return LibraryGroupId("manual-merge")
    }

    override suspend fun splitMembers(
        profileId: LibraryProfileId,
        sourceGroupId: LibraryGroupId,
        movedSourceKeys: List<SourceBookKey>,
    ): LibraryGroupId {
        splitSourceGroupId = sourceGroupId
        splitSourceKeys = movedSourceKeys
        return LibraryGroupId("manual-split")
    }
}

private fun knownSnapshot(profileId: LibraryProfileId): SourceBookSnapshot {
    val adapterId = LibraryAdapterId("existing-reader")
    val connectionId = SourceConnectionId("existing-connection")
    val key = SourceBookKey(
        profileId = profileId,
        adapterId = adapterId,
        accountIdentity = SourceAccountIdentity.Portable("existing-backend", "account"),
        nativeBookId = NativeBookId("existing-copy"),
    )
    val resource = SourceResourceRef(key, "existing-epub")
    return SourceBookSnapshot(
        source = SourceBookRef(key, connectionId),
        metadata = SourceBookMetadata("Existing copy"),
        resources = emptyList(),
        identityEvidence = listOf(
            SourceIdentityEvidence.FileFingerprint(
                resource = resource,
                algorithm = "sha-256-v1",
                hash = "same-hash",
                scope = FingerprintScope.WholeFile,
                verification = FingerprintVerification.Verified,
            ),
        ),
        status = presentStatus(),
    )
}

private fun presentStatus() = SourceSnapshotStatus(
    observedAt = Instant.parse("2026-09-24T00:00:00Z"),
    presence = SourcePresence.Present,
    isAuthoritative = true,
)
