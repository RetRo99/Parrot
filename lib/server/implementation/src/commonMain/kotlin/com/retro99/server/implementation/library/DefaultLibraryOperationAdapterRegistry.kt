package com.retro99.server.implementation.library

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryOperationAdapterRegistry::class])
class DefaultLibraryOperationAdapterRegistry(
    @Provided adapters: List<LibraryOperationAdapter>,
) : LibraryOperationAdapterRegistry {
    private val adaptersById = adapters.associateBy(LibraryOperationAdapter::adapterId)

    init {
        require(adaptersById.size == adapters.size) {
            "Library operation adapter IDs must be unique"
        }
    }

    override fun adapter(
        adapterId: LibraryAdapterId,
    ): LibraryOperationAdapter? = adaptersById[adapterId]

    override fun adapters(): List<LibraryOperationAdapter> = adaptersById.values
        .sortedBy { adapter -> adapter.adapterId.value }
}
