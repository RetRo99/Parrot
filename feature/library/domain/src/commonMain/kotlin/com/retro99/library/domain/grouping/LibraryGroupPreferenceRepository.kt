package com.retro99.library.domain.grouping

import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey

interface LibraryGroupPreferenceRepository {
    /** Select the source used by default for one media type within a group. */
    suspend fun setPreferredMediaSource(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
        mediaType: String,
        sourceKey: SourceBookKey,
    ): Boolean
}
