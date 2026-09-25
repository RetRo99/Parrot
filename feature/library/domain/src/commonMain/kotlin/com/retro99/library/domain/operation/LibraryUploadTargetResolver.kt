package com.retro99.library.domain.operation

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryUploadDestinationCandidate
import com.retro99.server.api.library.LibraryUploadDestinationProposal
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceAvailability

enum class LibraryUploadUnavailableReason {
    ProfileMismatch,
    SourceConnectionUnavailable,
    SourceReplicaUnavailable,
    NoUploadDestination,
    AdapterNotRegistered,
    DestinationConnectionUnavailable,
    DestinationAccountUnauthenticated,
    UnsupportedFormat,
    UploadCapabilityNotReported,
    UploadCapabilityDisabled,
}

data class ResolvedLibraryUploadTarget(
    val source: LibraryUploadSource,
    val target: LibraryOperationTarget.UploadDestination,
    val adapterId: LibraryAdapterId,
)

data class UnavailableLibraryUploadTarget(
    val source: LibraryUploadSource,
    val candidate: LibraryUploadDestinationCandidate?,
    val reason: LibraryUploadUnavailableReason,
    val detail: String? = null,
)

data class LibraryUploadTargetResolution(
    val available: List<ResolvedLibraryUploadTarget>,
    val unavailable: List<UnavailableLibraryUploadTarget>,
)

/** Pure policy for turning registered adapter proposals into executable upload targets. */
object LibraryUploadTargetResolver {
    fun resolve(
        profileId: LibraryProfileId,
        sources: List<LibraryUploadSource>,
        proposals: List<LibraryUploadDestinationProposal>,
        registeredAdapters: Set<LibraryAdapterId>,
        connectedConnections: Set<SourceConnectionId>,
        authenticatedAccounts: Set<SourceAccountIdentity.Portable>,
        sourceAvailability: Map<
            LibraryOperationTarget.DeviceReplica,
            SourceResourceAvailability,
        >,
    ): LibraryUploadTargetResolution {
        val availableTargets = mutableListOf<ResolvedLibraryUploadTarget>()
        val unavailableTargets = mutableListOf<UnavailableLibraryUploadTarget>()

        sources.forEach { source ->
            val sourceRef = source.sourceReplica.source
            when {
                sourceRef.key.profileId != profileId -> unavailableTargets += unavailable(
                    source,
                    LibraryUploadUnavailableReason.ProfileMismatch,
                )
                sourceRef.connectionId == null || sourceRef.connectionId !in connectedConnections ->
                    unavailableTargets += unavailable(
                        source,
                        LibraryUploadUnavailableReason.SourceConnectionUnavailable,
                    )
                sourceAvailability[source.sourceReplica] !=
                    SourceResourceAvailability.DevicePresent -> unavailableTargets += unavailable(
                    source,
                    LibraryUploadUnavailableReason.SourceReplicaUnavailable,
                )
                else -> {
                    val matchingProposals = proposals.filter { proposal ->
                        proposal.candidate.source == source
                    }
                    if (matchingProposals.isEmpty()) {
                        unavailableTargets += unavailable(
                            source,
                            LibraryUploadUnavailableReason.NoUploadDestination,
                        )
                    }
                    matchingProposals.forEach { proposal ->
                        resolveProposal(
                            source = source,
                            proposal = proposal,
                            registeredAdapters = registeredAdapters,
                            connectedConnections = connectedConnections,
                            authenticatedAccounts = authenticatedAccounts,
                            availableTargets = availableTargets,
                            unavailableTargets = unavailableTargets,
                        )
                    }
                }
            }
        }

        return LibraryUploadTargetResolution(
            available = availableTargets,
            unavailable = unavailableTargets,
        )
    }

    private fun resolveProposal(
        source: LibraryUploadSource,
        proposal: LibraryUploadDestinationProposal,
        registeredAdapters: Set<LibraryAdapterId>,
        connectedConnections: Set<SourceConnectionId>,
        authenticatedAccounts: Set<SourceAccountIdentity.Portable>,
        availableTargets: MutableList<ResolvedLibraryUploadTarget>,
        unavailableTargets: MutableList<UnavailableLibraryUploadTarget>,
    ) {
        val candidate = proposal.candidate
        val destination = candidate.target
        val uploadAvailability = proposal.availability.filter { availability ->
            availability.operation == LibraryOperation.Upload
        }
        val failure = when {
            candidate.proposedBy !in registeredAdapters ->
                LibraryUploadUnavailableReason.AdapterNotRegistered
            !candidate.acceptedFormat.equals(source.format, ignoreCase = true) ->
                LibraryUploadUnavailableReason.UnsupportedFormat
            destination.destinationConnectionId !in connectedConnections ->
                LibraryUploadUnavailableReason.DestinationConnectionUnavailable
            destination.destinationAccount !in authenticatedAccounts ->
                LibraryUploadUnavailableReason.DestinationAccountUnauthenticated
            uploadAvailability.isEmpty() ->
                LibraryUploadUnavailableReason.UploadCapabilityNotReported
            uploadAvailability.none { availability -> availability.isAvailable } ->
                LibraryUploadUnavailableReason.UploadCapabilityDisabled
            else -> null
        }

        if (failure == null) {
            availableTargets += ResolvedLibraryUploadTarget(
                source = source,
                target = destination,
                adapterId = candidate.proposedBy,
            )
        } else {
            val availabilityReason = uploadAvailability.firstOrNull { availability ->
                !availability.isAvailable
            }?.reason
            unavailableTargets += unavailable(
                source = source,
                reason = failure,
                candidate = candidate,
                detail = availabilityReason,
            )
        }
    }

    private fun unavailable(
        source: LibraryUploadSource,
        reason: LibraryUploadUnavailableReason,
        candidate: LibraryUploadDestinationCandidate? = null,
        detail: String? = null,
    ) = UnavailableLibraryUploadTarget(
        source = source,
        candidate = candidate,
        reason = reason,
        detail = detail,
    )
}
