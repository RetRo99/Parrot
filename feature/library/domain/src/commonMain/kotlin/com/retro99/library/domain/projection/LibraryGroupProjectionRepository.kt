package com.retro99.library.domain.projection

import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import kotlinx.coroutines.flow.Flow

interface LibraryGroupProjectionRepository {
    fun observeGroups(profileId: LibraryProfileId): Flow<List<LibraryBookGroup>>

    fun observeActiveGroups(): Flow<List<LibraryBookGroup>>

    suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryBookGroup?
}

class ObserveLibraryGroupsUseCase(
    private val repository: LibraryGroupProjectionRepository,
) {
    operator fun invoke(profileId: LibraryProfileId): Flow<List<LibraryBookGroup>> =
        repository.observeGroups(profileId)
}

class ObserveActiveLibraryGroupsUseCase(
    private val repository: LibraryGroupProjectionRepository,
) {
    operator fun invoke(): Flow<List<LibraryBookGroup>> = repository.observeActiveGroups()
}

class GetLibraryGroupUseCase(
    private val repository: LibraryGroupProjectionRepository,
) {
    suspend operator fun invoke(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryBookGroup? = repository.getGroup(profileId, groupId)
}
