package com.retro99.library.domain.operation

import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryUploadDestinationCandidate
import com.retro99.server.api.library.LibraryUploadDestinationProposal
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryUploadTargetResolverTest {
    private val profileId = LibraryProfileId("profile-a")
    private val sourceConnection = SourceConnectionId("local-connection")
    private val destinationConnection = SourceConnectionId("remote-connection")
    private val adapterId = LibraryAdapterId("arbitrary-upload-adapter")
    private val destinationAccount = SourceAccountIdentity.Portable("remote", "account")

    @Test
    fun arbitraryRegisteredAdapterResolvesAnExactUploadTarget() {
        // Given
        val source = uploadSource("book-1", "resource-1")
        val proposal = proposal(source, adapterId)

        // When
        val result = resolve(
            sources = listOf(source),
            proposals = listOf(proposal),
            registeredAdapters = setOf(adapterId),
        )

        // Then
        assertEquals(1, result.available.size)
        assertEquals(proposal.candidate.target, result.available.single().target)
        assertEquals(source, result.available.single().source)
        assertTrue(result.unavailable.isEmpty())
    }

    @Test
    fun disabledUploadCapabilityReturnsItsExplicitReason() {
        // Given
        val source = uploadSource("book-1", "resource-1")
        val proposal = proposal(
            source = source,
            proposedBy = adapterId,
            availability = listOf(
                OperationAvailability(
                    operation = LibraryOperation.Upload,
                    isAvailable = false,
                    reason = "account quota reached",
                ),
            ),
        )

        // When
        val result = resolve(
            sources = listOf(source),
            proposals = listOf(proposal),
            registeredAdapters = setOf(adapterId),
        )

        // Then
        assertTrue(result.available.isEmpty())
        assertEquals(
            LibraryUploadUnavailableReason.UploadCapabilityDisabled,
            result.unavailable.single().reason,
        )
        assertEquals("account quota reached", result.unavailable.single().detail)
    }

    @Test
    fun sameFormatResourcesResolveToDistinctExactTargets() {
        // Given
        val firstSource = uploadSource("book-1", "epub-main")
        val secondSource = uploadSource("book-1", "epub-large-print")
        val proposals = listOf(
            proposal(firstSource, adapterId),
            proposal(secondSource, adapterId),
        )

        // When
        val result = resolve(
            sources = listOf(firstSource, secondSource),
            proposals = proposals,
            registeredAdapters = setOf(adapterId),
        )

        // Then
        assertEquals(2, result.available.size)
        assertEquals(
            setOf("epub-main", "epub-large-print"),
            result.available.map { target -> target.target.resource.nativeResourceId }.toSet(),
        )
        assertEquals(
            setOf("replica-epub-main", "replica-epub-large-print"),
            result.available.map { target -> target.source.sourceReplica.replicaId.value }.toSet(),
        )
    }

    @Test
    fun disconnectedAndUnregisteredDestinationsAreUnavailable() {
        // Given
        val source = uploadSource("book-1", "resource-1")
        val proposal = proposal(source, adapterId)

        // When
        val disconnectedSource = resolve(
            sources = listOf(source),
            proposals = listOf(proposal),
            registeredAdapters = setOf(adapterId),
            connectedConnections = setOf(destinationConnection),
        )
        val unregisteredAdapter = resolve(
            sources = listOf(source),
            proposals = listOf(proposal),
            registeredAdapters = emptySet(),
        )
        val disconnectedDestination = resolve(
            sources = listOf(source),
            proposals = listOf(proposal),
            registeredAdapters = setOf(adapterId),
            connectedConnections = setOf(sourceConnection),
        )

        // Then
        assertEquals(
            LibraryUploadUnavailableReason.SourceConnectionUnavailable,
            disconnectedSource.unavailable.single().reason,
        )
        assertEquals(
            LibraryUploadUnavailableReason.AdapterNotRegistered,
            unregisteredAdapter.unavailable.single().reason,
        )
        assertEquals(
            LibraryUploadUnavailableReason.DestinationConnectionUnavailable,
            disconnectedDestination.unavailable.single().reason,
        )
    }

    @Test
    fun wrongProfileAccountAndFormatCannotResolve() {
        // Given
        val source = uploadSource("book-1", "resource-1")
        val proposal = proposal(source, adapterId)

        // When
        val wrongProfile = resolve(
            sources = listOf(source),
            proposals = listOf(proposal),
            registeredAdapters = setOf(adapterId),
            requestedProfile = LibraryProfileId("profile-b"),
        )
        val unauthenticatedAccount = resolve(
            sources = listOf(source),
            proposals = listOf(proposal),
            registeredAdapters = setOf(adapterId),
            authenticatedAccounts = emptySet(),
        )
        val unsupportedFormat = resolve(
            sources = listOf(source),
            proposals = listOf(proposal.copy(
                candidate = proposal.candidate.copy(acceptedFormat = "mobi"),
            )),
            registeredAdapters = setOf(adapterId),
        )

        // Then
        assertEquals(
            LibraryUploadUnavailableReason.ProfileMismatch,
            wrongProfile.unavailable.single().reason,
        )
        assertEquals(
            LibraryUploadUnavailableReason.DestinationAccountUnauthenticated,
            unauthenticatedAccount.unavailable.single().reason,
        )
        assertEquals(
            LibraryUploadUnavailableReason.UnsupportedFormat,
            unsupportedFormat.unavailable.single().reason,
        )
    }

    @Test
    fun absentProposalAndDeviceReplicaHaveExplicitUnavailableReasons() {
        val source = uploadSource("book-1", "resource-1")

        val noDestination = resolve(
            sources = listOf(source),
            proposals = emptyList(),
            registeredAdapters = setOf(adapterId),
        )
        val unavailableReplica = resolve(
            sources = listOf(source),
            proposals = listOf(proposal(source, adapterId)),
            registeredAdapters = setOf(adapterId),
            sourceAvailability = mapOf(
                source.sourceReplica to SourceResourceAvailability.Unknown,
            ),
        )

        assertEquals(
            LibraryUploadUnavailableReason.NoUploadDestination,
            noDestination.unavailable.single().reason,
        )
        assertEquals(
            LibraryUploadUnavailableReason.SourceReplicaUnavailable,
            unavailableReplica.unavailable.single().reason,
        )
    }

    private fun resolve(
        sources: List<LibraryUploadSource>,
        proposals: List<LibraryUploadDestinationProposal>,
        registeredAdapters: Set<LibraryAdapterId>,
        connectedConnections: Set<SourceConnectionId> = setOf(
            sourceConnection,
            destinationConnection,
        ),
        authenticatedAccounts: Set<SourceAccountIdentity.Portable> = setOf(destinationAccount),
        sourceAvailability: Map<
            LibraryOperationTarget.DeviceReplica,
            SourceResourceAvailability,
        > = sources.associate { source ->
            source.sourceReplica to SourceResourceAvailability.DevicePresent
        },
        requestedProfile: LibraryProfileId = profileId,
    ) = LibraryUploadTargetResolver.resolve(
        profileId = requestedProfile,
        sources = sources,
        proposals = proposals,
        registeredAdapters = registeredAdapters,
        connectedConnections = connectedConnections,
        authenticatedAccounts = authenticatedAccounts,
        sourceAvailability = sourceAvailability,
    )

    private fun proposal(
        source: LibraryUploadSource,
        proposedBy: LibraryAdapterId,
        availability: List<OperationAvailability> = listOf(
            OperationAvailability(LibraryOperation.Upload, isAvailable = true),
        ),
    ) = LibraryUploadDestinationProposal(
        candidate = LibraryUploadDestinationCandidate(
            proposedBy = proposedBy,
            source = source,
            target = LibraryOperationTarget.UploadDestination(
                source = source.sourceReplica.source,
                resource = source.sourceReplica.resource,
                assetId = source.assetId,
                sourceReplicaId = source.sourceReplica.replicaId,
                sourceStorageRef = source.sourceReplica.storageRef,
                destinationAdapterId = proposedBy,
                destinationAccount = destinationAccount,
                destinationConnectionId = destinationConnection,
            ),
            acceptedFormat = source.format,
        ),
        availability = availability,
    )

    private fun uploadSource(
        nativeBookId: String,
        resourceId: String,
    ): LibraryUploadSource {
        val source = SourceBookRef(
            key = SourceBookKey(
                profileId = profileId,
                adapterId = LibraryAdapterId("source-adapter"),
                accountIdentity = SourceAccountIdentity.Portable("source", "account"),
                nativeBookId = NativeBookId(nativeBookId),
            ),
            connectionId = sourceConnection,
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
}
