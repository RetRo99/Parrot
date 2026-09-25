package com.retro99.library.domain.grouping

import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import org.koin.core.annotation.Factory

@Factory
class SetPreferredLibraryMediaSourceUseCase(
    private val repository: LibraryGroupPreferenceRepository,
) {
    suspend operator fun invoke(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
        mediaType: String,
        sourceKey: SourceBookKey,
    ): Boolean {
        require(mediaType.isNotBlank()) { "Preferred media type cannot be blank" }
        require(sourceKey.profileId == profileId) {
            "A media preference cannot cross profiles"
        }
        return repository.setPreferredMediaSource(
            profileId = profileId,
            groupId = groupId,
            mediaType = mediaType.lowercase(),
            sourceKey = sourceKey,
        )
    }
}
