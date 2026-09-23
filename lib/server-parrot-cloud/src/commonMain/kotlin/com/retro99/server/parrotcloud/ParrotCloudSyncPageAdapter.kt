package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.data.SyncPullPage
import com.retro99.sync.domain.LibraryMutationSyncTransport
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.RemoteProgressSnapshot
import com.retro99.sync.domain.SyncChange
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Combines Parrot's library-mutation and progress change feeds into the shared page
 * contract. Payload decoding and local reconciliation stay with the caller.
 */
@Single
class ParrotCloudSyncPageAdapter(
    @Provided private val libraryMutationTransport: LibraryMutationSyncTransport,
    @Provided private val progressTransport: ProgressSyncTransport,
) {
    suspend fun fetchPage(
        cursor: String,
        limit: Int,
        reportApplying: () -> Unit,
        onLibraryMutationChange: suspend (SyncChange) -> Unit,
        onProgressChange: suspend (RemoteProgressSnapshot) -> Unit,
    ): SyncPullPage {
        val libraryMutationResponse = libraryMutationTransport.pull(
            cursor = cursor,
            limit = limit,
        )
        val libraryMutationChanges = libraryMutationResponse.changes.filter { change ->
            change.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
        }
        if (libraryMutationChanges.isNotEmpty()) reportApplying()
        for (change in libraryMutationChanges) {
            onLibraryMutationChange(change)
        }

        val progressPage = progressTransport.fetchChanges(
            cursor = cursor,
            limit = limit,
        )
        if (progressPage.changes.isNotEmpty()) reportApplying()
        for (change in progressPage.changes) {
            onProgressChange(change)
        }

        val currentCursor = cursor.toLongOrNull() ?: 0L
        val nextCursor = maxOf(
            libraryMutationResponse.nextCursor?.toLongOrNull() ?: currentCursor,
            progressPage.nextCursor?.toLongOrNull() ?: currentCursor,
        )
        return SyncPullPage(
            changeCount = libraryMutationChanges.size + progressPage.changes.size,
            nextCursor = nextCursor.toString(),
            hasMore = libraryMutationResponse.hasMore || progressPage.hasMore,
        )
    }
}
