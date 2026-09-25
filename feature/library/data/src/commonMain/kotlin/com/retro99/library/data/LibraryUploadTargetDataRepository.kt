package com.retro99.library.data

import com.retro99.library.domain.operation.LibraryUploadTargetRepository
import com.retro99.library.domain.operation.LibraryUploadTargetResolution
import com.retro99.library.domain.operation.LibraryUploadTargetResolver
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryUploadDestinationProposal
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceAvailability
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryUploadTargetRepository::class])
class LibraryUploadTargetDataRepository(
    @Provided private val adapterRegistry: LibraryOperationAdapterRegistry,
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
) : LibraryUploadTargetRepository {
    override suspend fun resolve(
        profileId: LibraryProfileId,
        sources: List<LibraryUploadSource>,
        sourceAvailability: Map<
            LibraryOperationTarget.DeviceReplica,
            SourceResourceAvailability,
        >,
    ): LibraryUploadTargetResolution {
        val adapters = adapterRegistry.adapters()
        val connectedRepositories = repositoryProvider.getBooksRepositories()
        val proposals = mutableListOf<LibraryUploadDestinationProposal>()
        sources.forEach { source ->
            adapters.forEach { adapter ->
                adapter.proposeUploadDestinations(source).forEach { candidate ->
                    proposals += LibraryUploadDestinationProposal(
                        candidate = candidate,
                        availability = adapter.availability(candidate.target),
                    )
                }
            }
        }
        return LibraryUploadTargetResolver.resolve(
            profileId = profileId,
            sources = sources,
            proposals = proposals,
            registeredAdapters = adapters.mapTo(mutableSetOf()) { adapter -> adapter.adapterId },
            connectedConnections = connectedRepositories.mapTo(mutableSetOf()) { repository ->
                SourceConnectionId(repository.serverId)
            },
            authenticatedAccounts = connectedRepositories.mapNotNullTo(mutableSetOf()) { repository ->
                repository.libraryAccountIdentity()
            }.filterIsInstance<SourceAccountIdentity.Portable>().toSet(),
            sourceAvailability = sourceAvailability,
        )
    }
}
