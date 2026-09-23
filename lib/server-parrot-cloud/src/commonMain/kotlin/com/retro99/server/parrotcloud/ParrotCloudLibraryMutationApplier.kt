package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.data.LibraryMutationApplier
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.data.SyncLibraryBookSnapshot
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Applies Parrot library-mutation responses to shared local state.
 * The shared library-mutation engine owns outbox state transitions and retry decisions.
 */
@Single
class ParrotCloudLibraryMutationApplier(
    @Provided private val libraryBookSyncApplier: LibraryBookSyncApplier,
) : LibraryMutationApplier {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun onAccepted(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        val payload = json.decodeFromString<ParrotCloudBookPayload>(entry.payload)
        libraryBookSyncApplier.applyAccepted(
            entry = entry,
            response = response,
            snapshot = payload.toSyncLibraryBookSnapshot(response.revision),
        )
    }

    override suspend fun onConflict(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        response.payload?.let { payload ->
            val book = json.decodeFromString<ParrotCloudBookPayload>(payload)
            libraryBookSyncApplier.applyRemote(
                book.toSyncLibraryBookSnapshot(response.revision),
            )
        }
    }
}

internal fun ParrotCloudBookPayload.toSyncLibraryBookSnapshot(
    revision: Long?,
): SyncLibraryBookSnapshot {
    return SyncLibraryBookSnapshot(
        libraryBookId = libraryBookId,
        cloudBookId = cloudBookId,
        contentHash = contentHash,
        contentHashAlgorithm = contentHashAlgorithm,
        title = title,
        author = author,
        format = format,
        remoteRevision = revision ?: remoteRevision,
        metadataJson = metadataJson,
    )
}
