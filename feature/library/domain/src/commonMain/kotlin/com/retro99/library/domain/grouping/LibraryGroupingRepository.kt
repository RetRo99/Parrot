package com.retro99.library.domain.grouping

import com.retro99.server.api.library.LibraryProfileId

interface LibraryGroupingRepository {
    suspend fun reconcileAutomaticGroups(
        profileId: LibraryProfileId,
        appliedAt: String,
    ): LibraryGroupingPlan
}

class ReconcileLibraryGroupsUseCase(
    private val repository: LibraryGroupingRepository,
) {
    suspend operator fun invoke(
        profileId: LibraryProfileId,
        appliedAt: String,
    ): LibraryGroupingPlan = repository.reconcileAutomaticGroups(profileId, appliedAt)
}
