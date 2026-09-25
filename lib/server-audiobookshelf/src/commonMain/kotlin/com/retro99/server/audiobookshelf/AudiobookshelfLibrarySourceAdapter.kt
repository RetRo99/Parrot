package com.retro99.server.audiobookshelf

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibrarySourceIdentityPromotionAdapter
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.ServerBookLibrarySourceAdapter
import com.retro99.server.api.ServerBook
import org.koin.core.annotation.Single

@Single(binds = [LibrarySourceAdapter::class])
class AudiobookshelfLibrarySourceAdapter :
    ServerBookLibrarySourceAdapter(),
    LibrarySourceIdentityPromotionAdapter {
    override val adapterId = LibraryAdapterId("audiobookshelf")

    override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ): SourceBookSnapshot {
        val snapshot = super.snapshot(source, book, status)
        return snapshot.copy(
            resources = snapshot.resources.map { resource ->
                resource.copy(
                    remoteResourceReference = RemoteResourceRef(
                        "/api/items/${book.uuid}/file/${resource.reference.nativeResourceId}",
                    ),
                )
            },
        )
    }

    override fun promoteUnresolvedSourceIdentity(
        unresolvedSource: SourceBookRef,
        portableAccountIdentity: com.retro99.server.api.library.SourceAccountIdentity.Portable,
    ): SourceBookRef? {
        if (!portableAccountIdentity.backendId.startsWith(BACKEND_INSTANCE_PREFIX)) return null
        return unresolvedSource.copy(
            key = unresolvedSource.key.copy(accountIdentity = portableAccountIdentity),
        )
    }

    private companion object {
        const val BACKEND_INSTANCE_PREFIX = "audiobookshelf-instance:"
    }
}
