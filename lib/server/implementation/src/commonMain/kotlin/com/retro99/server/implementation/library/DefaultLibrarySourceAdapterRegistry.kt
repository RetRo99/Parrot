package com.retro99.server.implementation.library

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.LibrarySourceAdapterRegistry
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Generic registration boundary; adding an adapter does not add a ServerType branch. */
@Single(binds = [LibrarySourceAdapterRegistry::class])
class DefaultLibrarySourceAdapterRegistry(
    @Provided adapters: List<LibrarySourceAdapter>,
) : LibrarySourceAdapterRegistry {
    private val adaptersById = adapters.associateBy(LibrarySourceAdapter::adapterId)

    init {
        require(adaptersById.size == adapters.size) {
            "Library source adapter IDs must be unique"
        }
    }

    override fun adapter(adapterId: LibraryAdapterId): LibrarySourceAdapter? =
        adaptersById[adapterId]
}
