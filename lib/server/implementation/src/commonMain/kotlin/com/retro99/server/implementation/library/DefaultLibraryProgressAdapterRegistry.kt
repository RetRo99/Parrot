package com.retro99.server.implementation.library

import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProgressAdapter
import com.retro99.server.api.library.LibraryProgressAdapterRegistry
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryProgressAdapterRegistry::class])
class DefaultLibraryProgressAdapterRegistry(
    @Provided adapters: List<LibraryProgressAdapter>,
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider? = null,
) : LibraryProgressAdapterRegistry {
    private val adaptersById = adapters.associateBy(LibraryProgressAdapter::adapterId)

    init {
        require(adaptersById.size == adapters.size) {
            "Library progress adapter IDs must be unique"
        }
    }

    override fun adapter(adapterId: LibraryAdapterId): LibraryProgressAdapter? =
        adaptersById[adapterId] ?: repositoryProvider?.let { provider ->
            ServerReaderLibraryProgressAdapter(adapterId, provider)
        }
}
